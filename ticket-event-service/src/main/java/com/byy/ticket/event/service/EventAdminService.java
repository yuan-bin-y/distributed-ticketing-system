package com.byy.ticket.event.service;
import com.byy.ticket.event.dto.event.EventDraftDTO;
import com.byy.ticket.event.vo.event.EventDraftVO;
/** 管理业务接口，Impl编排草稿事务和库存准备，Controller不直接操作数据库。 */
public interface EventAdminService {
    /** 原子创建活动、场次、票档及准备参数；相同请求返回原草稿。 */
    EventDraftVO create(EventDraftDTO request);
    /** 查询管理视图，包括尚未发布的草稿和库存准备进度。 */
    EventDraftVO get(Long id);
    /** 管理员核对后按原参数重新准备，不能覆盖已有库存。 */
    EventDraftVO prepare(Long id);
    /** 所有售卖票档准备成功才允许发布活动和场次。 */
    EventDraftVO publish(Long id);
}
