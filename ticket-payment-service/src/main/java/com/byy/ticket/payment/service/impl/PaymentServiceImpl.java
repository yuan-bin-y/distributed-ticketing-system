package com.byy.ticket.payment.service.impl;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.payment.config.PaymentProperties;
import com.byy.ticket.payment.dto.payment.*;
import com.byy.ticket.payment.exception.PaymentConflictException;
import com.byy.ticket.payment.mapper.*;
import com.byy.ticket.payment.model.*;
import com.byy.ticket.payment.service.PaymentService;
import com.byy.ticket.payment.vo.payment.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 支付本地事务：唯一键及行锁保证幂等，支付事实和通知进度一起保存。 */
@Service
public class PaymentServiceImpl implements PaymentService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
    private final PaymentMapper payments;
    private final PaymentReversalMapper reversals;
    private final Clock clock;
    private final PaymentProperties properties;

    /** 注入本服务 Mapper、固定时钟和模拟开关，不依赖其他服务数据库。 */
    public PaymentServiceImpl(PaymentMapper payments, PaymentReversalMapper reversals,
                              Clock clock, PaymentProperties properties) {
        this.payments = payments;
        this.reversals = reversals;
        this.clock = clock;
        this.properties = properties;
    }

    /** 首次创建检查未来期限；重复创建即使过期也核对原参数并返回原记录。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentVO create(PaymentCreateDTO request) {
        validateCreate(request);
        Payment candidate = new Payment();
        candidate.setPaymentNo(newNo());
        candidate.setOrderNo(request.orderNo());
        candidate.setUserId(request.userId());
        candidate.setAmount(request.amount());
        candidate.setStatus(PaymentStatus.CREATED.name());
        candidate.setExpiresAt(request.expiresAt());
        payments.insertOrKeep(candidate);
        Payment existing = payments.selectByOrderForUpdate(request.orderNo());
        if (existing == null) { throw new IllegalStateException("无法读取已创建的支付单"); }
        if (!candidate.getPaymentNo().equals(existing.getPaymentNo())) {
            requireSameRequest(existing, request);
        } else if (!request.expiresAt().isAfter(now())) {
            throw new PaymentConflictException("首次创建支付单的到期时间必须晚于当前时间");
        }
        return toVO(existing);
    }

    /** 在行锁下保存首次成功事实；重复成功不重置通知时间、次数或首次付款时间。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentVO simulateSuccess(Long userId, String paymentNo) {
        if (!properties.simulationEnabled()) { throw new ResourceNotFoundException("模拟支付未开启"); }
        validateUser(userId);
        validateNo(paymentNo, "支付单");
        Payment payment = payments.selectForUpdate(paymentNo);
        if (payment == null || !userId.equals(payment.getUserId())) {
            throw new ResourceNotFoundException("支付单不存在");
        }
        if (PaymentStatus.SUCCESS.name().equals(payment.getStatus())) { return toVO(payment); }
        LocalDateTime paidAt = now();
        if (!payment.getExpiresAt().isAfter(paidAt)) {
            throw new PaymentConflictException("支付单已到期，不能首次模拟付款");
        }
        if (payments.markSuccess(payment.getId(), paidAt) != 1) {
            throw new IllegalStateException("支付成功状态保存失败");
        }
        return toVO(payments.selectById(payment.getId()));
    }

    /** 订单侧核对入口；读取本库支付与冲正事实，不推断订单是否履约。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaymentVO getByOrder(String orderNo) {
        validateNo(orderNo, "订单");
        Payment payment = payments.selectByOrder(orderNo);
        if (payment == null) { throw new ResourceNotFoundException("支付单不存在"); }
        return toVO(payment);
    }

    /** 公共查询必须核对当前用户，其他用户和不存在的编号统一返回不存在。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PaymentVO getOwned(Long userId, String paymentNo) {
        validateUser(userId);
        validateNo(paymentNo, "支付单");
        Payment payment = payments.selectOwned(paymentNo, userId);
        if (payment == null) { throw new ResourceNotFoundException("支付单不存在"); }
        return toVO(payment);
    }

    /** 锁定支付单后创建并完成全额模拟冲正；保留 SUCCESS 支付事实和待通知进度。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentReversalVO reverse(PaymentReversalDTO request) {
        if (!properties.simulationEnabled()) { throw new ResourceNotFoundException("模拟冲正未开启"); }
        if (request == null) { throw new IllegalArgumentException("冲正请求不能为空"); }
        validateNo(request.paymentNo(), "支付单");
        if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 256) {
            throw new IllegalArgumentException("冲正原因必须为1至256个字符");
        }
        String reason = request.reason().strip();
        Payment payment = payments.selectForUpdate(request.paymentNo());
        if (payment == null) { throw new ResourceNotFoundException("支付单不存在"); }
        if (!PaymentStatus.SUCCESS.name().equals(payment.getStatus())) {
            throw new PaymentConflictException("尚未支付成功，不能冲正");
        }
        PaymentReversal reversal = reversals.selectByPayment(payment.getId());
        if (reversal != null) {
            if (!reason.equals(reversal.getReason())) {
                throw new PaymentConflictException("同一支付单的冲正原因与首次请求不一致");
            }
            return toReversalVO(payment, reversal);
        }
        reversal = new PaymentReversal();
        reversal.setReversalNo(newNo());
        reversal.setPaymentId(payment.getId());
        reversal.setAmount(payment.getAmount());
        reversal.setReason(reason);
        reversal.setStatus("PENDING");
        reversals.insert(reversal);
        if (reversals.markSuccess(reversal.getId(), now()) != 1) {
            throw new IllegalStateException("模拟冲正结果保存失败");
        }
        return toReversalVO(payment, reversals.selectById(reversal.getId()));
    }

    /** 服务层校验也覆盖直接 Java 调用，防止金额精度或时间截断破坏幂等。 */
    private void validateCreate(PaymentCreateDTO request) {
        if (request == null) { throw new IllegalArgumentException("支付请求不能为空"); }
        validateNo(request.orderNo(), "订单");
        validateUser(request.userId());
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("金额必须大于零、最多两位小数且不超过数据库上限");
        }
        LocalDateTime expiresAt = request.expiresAt();
        if (expiresAt == null || expiresAt.getYear() < 1000 || expiresAt.getYear() > 9999
                || expiresAt.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("到期时间必须在MySQL支持范围内且精度不超过毫秒");
        }
    }

    /** 相同订单编号必须对应相同用户、金额和期限，金额按数值比较。 */
    private void requireSameRequest(Payment payment, PaymentCreateDTO request) {
        if (!payment.getUserId().equals(request.userId())
                || payment.getAmount().compareTo(request.amount()) != 0
                || !payment.getExpiresAt().equals(request.expiresAt())) {
            throw new PaymentConflictException("同一订单的支付参数与首次请求不一致");
        }
    }

    /** 编号遵循平台的32位小写十六进制UUID约定。 */
    private void validateNo(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(label + "编号必须为32位小写十六进制字符");
        }
    }

    /** 用户ID必须为正整数。 */
    private void validateUser(Long userId) {
        if (userId == null || userId < 1) { throw new IllegalArgumentException("用户ID必须大于零"); }
    }

    /** 拼装支付查询结果，附带独立的冲正状态，不覆盖原付款事实。 */
    private PaymentVO toVO(Payment payment) {
        PaymentReversal reversal = reversals.selectByPayment(payment.getId());
        return new PaymentVO(payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(),
                payment.getAmount(), payment.getStatus(), payment.getExpiresAt(), payment.getPaidAt(),
                payment.getNotifyStatus(), reversal == null ? null : reversal.getReversalNo(),
                reversal == null ? null : reversal.getStatus(), payment.getCreatedAt());
    }

    /** 冲正响应包含原订单与支付编号，调用方可以核对响应归属。 */
    private PaymentReversalVO toReversalVO(Payment payment, PaymentReversal reversal) {
        return new PaymentReversalVO(reversal.getReversalNo(), payment.getPaymentNo(), payment.getOrderNo(),
                reversal.getAmount(), reversal.getReason(), reversal.getStatus(),
                reversal.getCompletedAt(), reversal.getCreatedAt());
    }

    /** 返回固定东八区、毫秒精度的当前时间，与 DATETIME(3) 保持一致。 */
    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS); }

    /** 生成稳定业务编号，后续重试从数据库读取原编号。 */
    private String newNo() { return UUID.randomUUID().toString().replace("-", ""); }
}
