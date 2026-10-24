package com.byy.ticket.order.purchase;
import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
/** 受理快照固定价格、规则、数量和首次期限，后台恢复不重新计算这些值。 */
@Data @TableName("t_purchase_acceptance")
public class PurchaseAcceptance {
    @TableId(type=IdType.INPUT) private String requestId;
    private Long userId;private String idempotencyKey;private Long ticketTierId;private Integer quantity;
    private String requestJson;private String ruleJson;private BigDecimal amount;
    private LocalDateTime expiresAt;private LocalDateTime acceptedAt;private String status;private String lastError;
    private Integer attemptCount;private LocalDateTime nextAttemptAt;private String leaseToken;private LocalDateTime leaseUntil;
    private String traceParent;private String traceState;
}
