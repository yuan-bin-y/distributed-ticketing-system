package com.byy.ticket.inventory.service;
import com.byy.ticket.inventory.dto.inventory.InitializeStockDTO;
import com.byy.ticket.inventory.mapper.TicketStockMapper;
import com.byy.ticket.inventory.exception.InventoryConflictException;
import com.byy.ticket.inventory.vo.inventory.TicketStockVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 初始化事实与库存同一本地事务提交；已存在只核对原参数。 */
@Service
public class StockInitializationService {
    private final TicketStockMapper stocks;
    public StockInitializationService(TicketStockMapper stocks){this.stocks=stocks;}
    @Transactional
    public TicketStockVO initialize(InitializeStockDTO request){
        if(request==null||request.ticketTierId()==null||request.ticketTierId()<1||request.sessionId()==null||request.sessionId()<1
                ||request.totalQuantity()==null||request.totalQuantity()<1)throw new IllegalArgumentException("初始化参数无效");
        stocks.initialize(request.ticketTierId(),request.sessionId(),request.totalQuantity());
        var stock=stocks.selectForUpdate(request.ticketTierId());
        if(!request.sessionId().equals(stock.getSessionId())||!request.totalQuantity().equals(stock.getTotalQuantity()))
            throw new InventoryConflictException("同一票档初始化场次和数量必须保持一致");
        return new TicketStockVO(stock.getTicketTierId(),stock.getSessionId(),stock.getTotalQuantity(),
                stock.getAvailableQuantity(),stock.getReservedQuantity(),stock.getSoldQuantity());
    }
}
