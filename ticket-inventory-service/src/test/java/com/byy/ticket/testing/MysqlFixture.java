package com.byy.ticket.testing;

import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;

/** 只创建并删除本次随机测试库；不复用业务库，也不记录密码。 */
public final class MysqlFixture implements AutoCloseable {
    public final DataSource dataSource;
    private final String schema,base,user,password;
    public MysqlFixture() throws Exception {
        schema="ticket_test_"+UUID.randomUUID().toString().replace("-","");
        base=System.getenv().getOrDefault("TICKET_TEST_MYSQL_BASE","jdbc:mysql://127.0.0.1:3306/");
        if(!base.matches("jdbc:mysql://[^/?]+/"))throw new IllegalArgumentException("测试地址必须为不含库名的MySQL JDBC地址");
        user=required("LOCAL_MYSQL_USERNAME");password=required("LOCAL_MYSQL_PASSWORD");
        try(var db=DriverManager.getConnection(base,user,password);var sql=db.createStatement()) {sql.execute("CREATE DATABASE "+schema);}
        dataSource=new DriverManagerDataSource(base+schema+"?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true",user,password);
        try {Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();}
        catch(Exception failure){close();throw failure;}
    }
    public SqlSessionFactory factory() throws Exception {
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(dataSource);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);return factory.getObject();
    }
    public void close() throws Exception {
        if(!schema.matches("ticket_test_[a-f0-9]{32}"))throw new IllegalStateException("拒绝删除非测试库");
        try(var db=DriverManager.getConnection(base,user,password);var sql=db.createStatement()) {sql.execute("DROP DATABASE "+schema);}
    }
    private String required(String name) {var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("缺少测试环境变量："+name);return value;}
}
