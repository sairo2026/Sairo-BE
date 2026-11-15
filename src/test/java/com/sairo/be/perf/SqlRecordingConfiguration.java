package com.sairo.be.perf;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class SqlRecordingConfiguration {

  @Bean
  static BeanPostProcessor sqlRecordingPostProcessor() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !Proxy.isProxyClass(bean.getClass())) {
          return wrapDataSource(dataSource);
        }
        return bean;
      }
    };
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getTargetException();
    }
  }

  private static DataSource wrapDataSource(DataSource target) {
    return (DataSource)
        Proxy.newProxyInstance(
            SqlRecordingConfiguration.class.getClassLoader(),
            new Class<?>[] {DataSource.class, Closeable.class},
            (proxy, method, args) -> {
              if (method.getName().equals("close") && method.getParameterCount() == 0) {
                if (target instanceof Closeable closeable) {
                  closeable.close();
                }
                return null;
              }
              Object result = invoke(target, method, args);
              if (method.getName().equals("getConnection") && result instanceof Connection c) {
                return wrapConnection(c);
              }
              return result;
            });
  }

  private static Connection wrapConnection(Connection target) {
    return (Connection)
        Proxy.newProxyInstance(
            SqlRecordingConfiguration.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              Object result = invoke(target, method, args);
              if (method.getName().equals("prepareStatement")
                  && result instanceof PreparedStatement ps) {
                return wrapPrepared(ps, (String) args[0]);
              }
              if (method.getName().equals("createStatement") && result instanceof Statement st) {
                return wrapStatement(st);
              }
              return result;
            });
  }

  private static PreparedStatement wrapPrepared(PreparedStatement target, String sql) {
    List<SqlRecorder.ParamCall> params = new ArrayList<>();
    return (PreparedStatement)
        Proxy.newProxyInstance(
            SqlRecordingConfiguration.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.startsWith("set")
                  && args != null
                  && args.length >= 2
                  && args[0] instanceof Integer) {
                params.add(new SqlRecorder.ParamCall(method, args.clone()));
              } else if (name.equals("clearParameters")) {
                params.clear();
              }
              boolean timed =
                  name.equals("execute")
                      || name.equals("executeQuery")
                      || name.equals("executeUpdate")
                      || name.equals("executeBatch")
                      || name.equals("executeLargeUpdate")
                      || name.equals("executeLargeBatch");
              if (!timed) {
                return invoke(target, method, args);
              }
              long start = System.nanoTime();
              try {
                return invoke(target, method, args);
              } finally {
                SqlRecorder.record(sql, params, System.nanoTime() - start);
              }
            });
  }

  private static Statement wrapStatement(Statement target) {
    return (Statement)
        Proxy.newProxyInstance(
            SqlRecordingConfiguration.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, args) -> {
              String name = method.getName();
              boolean timed =
                  args != null
                      && args.length >= 1
                      && args[0] instanceof String
                      && (name.equals("execute")
                          || name.equals("executeQuery")
                          || name.equals("executeUpdate"));
              if (!timed) {
                return invoke(target, method, args);
              }
              long start = System.nanoTime();
              try {
                return invoke(target, method, args);
              } finally {
                SqlRecorder.record((String) args[0], List.of(), System.nanoTime() - start);
              }
            });
  }
}
