package com.lianxibu.site.service.learning;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="RI778_MYSQL",matches="true")
class LearningBatchedReadServiceMySqlTest extends LearningBatchedReadServiceTest {
    private static HikariDataSource pool;
    @Override DataSource source(){
        if(pool==null){
            String url=System.getenv("RI778_MYSQL_URL");if(url==null)throw new IllegalStateException("fictional native database required");
            var uri=java.net.URI.create(url.substring(5));
            if(!"mysql".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()!=13336||!"/fictional_ri778_learning_reader".equals(uri.getPath()))throw new IllegalStateException("dedicated fictional native database required");
            pool=new HikariDataSource();pool.setJdbcUrl(url);pool.setUsername("root");pool.setPassword("");
            pool.setMaximumPoolSize(4);pool.setMinimumIdle(0);pool.setPoolName("fictional-learning-reader");
        }return pool;
    }
    @Override boolean nativeJson(){return true;}
    @AfterAll static void closePool(){if(pool!=null){pool.close();pool=null;}}
}
