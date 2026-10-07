package com.byy.ticket.inventory.exception;

/** 正常的热点削峰拒绝，不代表库存服务宕机，也不代表库存不足。 */
public class HotStockBusyException extends org.springframework.dao.TransientDataAccessResourceException {
    public HotStockBusyException() {super("热点库存繁忙，请按原订单重试");}
}
