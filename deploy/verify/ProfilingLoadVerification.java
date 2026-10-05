import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** 保持基准配置，复测三个并发档；只读采样当前测试库的行锁等待。 */
public class ProfilingLoadVerification extends LoadVerification {
    final List<String> databaseSamples=new ArrayList<>(List.of("phase,orderWaitEdges,inventoryWaitEdges,globalRowLockWaits,globalRowLockTimeMs,error"));
    final List<String> phaseWindows=new ArrayList<>(List.of("phase,startEpochMs,endEpochMs"));
    ProfilingLoadVerification(Path root,int count){super(root,count);}
    @Override String workName(){return "profiling-load";}
    @Override int[] concurrencyLevels(){return new int[]{16,64,256};}
    @Override boolean additionalScenarios(){return false;}
    @Override void launch(String module,int port,String database,List<String> extra)throws Exception{
        var args=new ArrayList<>(extra);
        if(module.contains("order")||module.contains("inventory")){args.add("--ticket.perf.enabled=true");args.add("--logging.level.ticket.performance=INFO");}
        super.launch(module,port,database,args);
    }
    @Override Batch load(String name,int concurrency,int count,long tier,boolean duplicate)throws Exception{
        long phaseStart=System.currentTimeMillis();
        try(var connection=java.sql.DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD"))){
            var executor=Executors.newSingleThreadScheduledExecutor();
            var rows=new ConcurrentLinkedQueue<String>();
            executor.scheduleAtFixedRate(()->{
                long orderLocks=0,inventoryLocks=0,waits=0,time=0;
                try{
                    try(var sql=connection.prepareStatement("SELECT COALESCE(SUM(l.OBJECT_SCHEMA=?),0),COALESCE(SUM(l.OBJECT_SCHEMA=?),0) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID")){
                        sql.setQueryTimeout(2);sql.setString(1,orderSchema);sql.setString(2,inventorySchema);
                        try(var result=sql.executeQuery()){result.next();orderLocks=result.getLong(1);inventoryLocks=result.getLong(2);}
                    }
                    try(var sql=connection.createStatement();var result=sql.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')")){
                        while(result.next()){if(result.getString(1).equalsIgnoreCase("Innodb_row_lock_waits"))waits=result.getLong(2);else time=result.getLong(2);}
                    }
                    rows.add(name+","+orderLocks+","+inventoryLocks+","+waits+","+time+",");
                }catch(Exception failure){rows.add(name+",0,0,0,0,"+failure.getClass().getSimpleName());}
            },0,250,TimeUnit.MILLISECONDS);
            try{return super.load(name,concurrency,count,tier,duplicate);}
            finally{long phaseEnd=System.currentTimeMillis();executor.shutdown();if(!executor.awaitTermination(5,TimeUnit.SECONDS))executor.shutdownNow();databaseSamples.addAll(rows);Files.write(directory.resolve("database-waits.csv"),databaseSamples);phaseWindows.add(name+","+phaseStart+","+phaseEnd);Files.write(directory.resolve("phase-windows.csv"),phaseWindows);}
        }
    }
    public static void main(String[] args)throws Exception{
        var test=new ProfilingLoadVerification(Path.of(args[0]).toAbsolutePath(),Integer.parseInt(args[1]));
        try{test.run();}finally{try{test.saveReport();}finally{test.cleanup();}}
        System.out.println("PASS profiling integrity; report="+test.directory.resolve("summary.json"));
    }
}
