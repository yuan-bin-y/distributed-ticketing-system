package com.byy.ticket.event.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.byy.ticket.event.cache.*;
import com.byy.ticket.event.query.*;
import com.byy.ticket.event.service.impl.EventDatabaseQueries;
import com.byy.ticket.testing.MysqlFixture;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@EnabledIfSystemProperty(named = "ticket.test.mysql", matches = "true")
class EventBrowseIntegrationTest {
    static MysqlFixture db;
    static AnnotationConfigApplicationContext context;
    static EventBrowseService browse;

    @Configuration
    @MapperScan({
        "com.byy.ticket.event.mapper",
        "com.byy.ticket.event.policy",
        "com.byy.ticket.event.query"
    })
    @Import({EventBrowseService.class, EventDatabaseQueries.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return db.dataSource;
        }

        @Bean
        SqlSessionFactory factory() throws Exception {
            return db.factory();
        }

        @Bean
        ObjectMapper json() {
            return new ObjectMapper();
        }

        @Bean
        EventQueryCache cache() {
            return new EventQueryCache(
                    mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                    json(),
                    false,
                    "test:",
                    new EventCacheTtl(1000),
                    100,
                    1000,
                    4);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        db = new MysqlFixture();
        context = new AnnotationConfigApplicationContext(Config.class);
        browse = context.getBean(EventBrowseService.class);
        var sql = new JdbcTemplate(db.dataSource);
        sql.update(
                "INSERT INTO t_event(id,name,category,status)"
                    + " VALUES(1,'large','CONCERT','PUBLISHED'),(2,'hidden','CONCERT','DRAFT')");
        for (int i = 1; i <= 250; i++)
            sql.update(
                    "INSERT INTO"
                        + " t_event_session(id,event_id,name,venue_name,venue_address,start_time,end_time,sale_start_time,sale_end_time,status)"
                        + " VALUES(?,1,'session','venue','address','2030-01-02','2030-01-03','2030-01-01','2030-01-02','PUBLISHED')",
                    i);
        for (int i = 1; i <= 235; i++)
            sql.update(
                    "INSERT INTO t_ticket_tier(id,session_id,name,price,enabled)"
                        + " VALUES(?,1,?,10,1)",
                    i,
                    "tier_" + i);
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (db != null) db.close();
    }

    @Test
    void deepCursorReturnsEveryRecordOnceWithBoundedResponse() {
        long after = 0;
        int total = 0;
        var seen = new java.util.HashSet<Long>();
        boolean more;
        do {
            var page = browse.sessions(1, after, 37);
            assertTrue(page.records().size() <= 37);
            page.records().forEach(s -> assertTrue(seen.add(s.id())));
            total += page.records().size();
            after = page.nextCursor();
            more = page.hasMore();
        } while (more);
        assertEquals(250, total);
        assertTrue(browse.sessions(1, after, 20).records().isEmpty());
    }

    @Test
    void tiersLoadOnlyRequestedSessionAndRespectHardLimit() {
        assertEquals(100, browse.tiers(1, 1, 0, 100).records().size());
        assertTrue(browse.tiers(1, 2, 0, 20).records().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> browse.sessions(1, 0, 101));
        assertThrows(RuntimeException.class, () -> browse.sessions(2, 0, 20));
    }

    @Test
    void purchaseJoinUsesCurrentPublishedPolicyAndRejectsHiddenEvent() {
        var sql = new JdbcTemplate(db.dataSource);
        var query = context.getBean(EventDatabaseQueries.class);
        assertEquals(0L, query.getPurchaseRule(1L).ruleVersion());
        sql.update(
                "INSERT INTO"
                    + " t_purchase_policy(event_id,version,max_sessions,max_per_order,status,note)"
                    + " VALUES(1,1,3,2,'PUBLISHED','test')");
        sql.update("UPDATE t_event SET purchase_policy_version=1 WHERE id=1");
        try {
            assertEquals(1L, query.getPurchaseRule(1L).ruleVersion());
            assertEquals(2, query.getPurchaseRule(1L).maxPerOrder());
            sql.update("UPDATE t_event SET status='DRAFT' WHERE id=1");
            assertThrows(
                    com.byy.ticket.common.exception.ResourceNotFoundException.class,
                    () -> query.getPurchaseRule(1L));
        } finally {
            sql.update(
                    "UPDATE t_event SET status='PUBLISHED',purchase_policy_version=0 WHERE id=1");
        }
    }
}
