package com.byy.ticket.event.policy;

import com.byy.ticket.testing.MysqlFixture;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="ticket.test.mysql",matches="true")
class PurchasePolicyIntegrationTest {
    static MysqlFixture db;static AnnotationConfigApplicationContext context;static PurchasePolicyService service;static JdbcTemplate sql;
    @Configuration @EnableTransactionManagement
    @MapperScan({"com.byy.ticket.event.mapper","com.byy.ticket.event.policy"}) @Import(PurchasePolicyService.class)
    static class Config {
        @Bean DataSource dataSource(){return db.dataSource;}
        @Bean SqlSessionFactory factory()throws Exception{return db.factory();}
        @Bean PlatformTransactionManager transactions(){return new DataSourceTransactionManager(db.dataSource);}
    }
    @BeforeAll static void start()throws Exception{db=new MysqlFixture();context=new AnnotationConfigApplicationContext(Config.class);service=context.getBean(PurchasePolicyService.class);sql=new JdbcTemplate(db.dataSource);sql.update("INSERT INTO t_event(id,name,category,status) VALUES(1,'rules','CONCERT','PUBLISHED'),(2,'rollback','CONCERT','PUBLISHED')");}
    @AfterAll static void stop()throws Exception{if(context!=null)context.close();if(db!=null)db.close();}
    @Test void draftHasNoEffectAndPublishedVersionCannotBeEdited(){
        var draft=service.draft(1,new PurchasePolicyService.Draft(3,2,"首次规则"));
        assertEquals(0L,sql.queryForObject("SELECT purchase_policy_version FROM t_event WHERE id=1",Long.class));
        service.publish(1,draft.version());assertEquals(draft.version(),sql.queryForObject("SELECT purchase_policy_version FROM t_event WHERE id=1",Long.class));
        assertThrows(RuntimeException.class,()->service.publish(1,draft.version()));assertEquals(1,service.list(1).size());
    }
    @Test void rollbackCreatesNewVersionAndRetainsBothHistoricalRules(){
        var v1=service.draft(2,new PurchasePolicyService.Draft(3,2,"旧规则"));service.publish(2,v1.version());
        var v2=service.draft(2,new PurchasePolicyService.Draft(1,1,"新规则"));service.publish(2,v2.version());
        var restored=service.rollback(2,v1.version());assertTrue(restored.version()>v2.version());assertEquals(3,restored.maxSessions());
        assertEquals(3,service.list(2).size());assertEquals(restored.version(),sql.queryForObject("SELECT purchase_policy_version FROM t_event WHERE id=2",Long.class));
    }
    @Test void invalidPolicyNeverLeavesDraft(){assertThrows(IllegalArgumentException.class,()->service.draft(1,new PurchasePolicyService.Draft(0,1,"invalid")));}
}
