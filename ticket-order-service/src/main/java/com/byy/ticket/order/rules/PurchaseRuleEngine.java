package com.byy.ticket.order.rules;

import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.exception.OrderConflictException;
import java.time.LocalDateTime;
import java.util.*;
import org.drools.model.codegen.ExecutableModelProject;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.io.ResourceFactory;
import org.kie.internal.utils.KieHelper;
import org.springframework.stereotype.Component;

/** DRL 在启动时编译一次，单次购买新建会话并释放，用户之间不共享可变事实。 */
@Component
public class PurchaseRuleEngine {
    private final KieBase rules=new KieHelper().addResource(ResourceFactory.newClassPathResource("rules/purchase.drl"),ResourceType.DRL).build(ExecutableModelProject.class);
    public List<String> evaluate(PurchaseFacts facts){
        var session=rules.newKieSession();List<String> errors=new ArrayList<>();
        try{session.setGlobal("errors",errors);session.insert(facts);session.fireAllRules();return List.copyOf(errors);}finally{session.dispose();}
    }
    /** 这里只做无历史额度的价格预览/准入预检查；正式累计限购在订单本地事务中再次核对。 */
    public void checkRequest(int quantity,TicketPurchaseRuleResponse rule,LocalDateTime now){
        check(new PurchaseFacts(quantity,0,0,false,rule.purchaseLimit(),rule.maxSessions(),rule.maxPerOrder(),now,rule.saleStartTime(),rule.saleEndTime()));
    }
    public void check(PurchaseFacts facts){var errors=evaluate(facts);if(!errors.isEmpty())throw new OrderConflictException(String.join("；",errors));}
}
