/*
 * Copyright 1999-2011 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.pool;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.JMException;
import javax.management.openmbean.CompositeDataSupport;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.logging.Log;
import com.alibaba.druid.logging.LogFactory;
import com.alibaba.druid.pool.vendor.InformixExceptionSorter;
import com.alibaba.druid.pool.vendor.MSSQLValidConnectionChecker;
import com.alibaba.druid.pool.vendor.MySqlExceptionSorter;
import com.alibaba.druid.pool.vendor.MySqlValidConnectionChecker;
import com.alibaba.druid.pool.vendor.OracleExceptionSorter;
import com.alibaba.druid.pool.vendor.OracleValidConnectionChecker;
import com.alibaba.druid.pool.vendor.SybaseExceptionSorter;
import com.alibaba.druid.proxy.DruidDriver;
import com.alibaba.druid.proxy.jdbc.DataSourceProxyConfig;
import com.alibaba.druid.stat.JdbcDataSourceStat;
import com.alibaba.druid.stat.JdbcStatManager;
import com.alibaba.druid.util.ConcurrentIdentityHashMap;
import com.alibaba.druid.util.JdbcUtils;

/**
 * @author wenshao<szujobs@hotmail.com>
 */
public class DruidDataSource extends DruidAbstractDataSource implements DruidDataSourceMBean, ManagedDataSource, Referenceable {

    private final static Log                                                LOG                   = LogFactory.getLog(DruidDataSource.class);

    // global instances
    private static final Object                                             PRESENT               = new Object();
    private static final ConcurrentIdentityHashMap<DruidDataSource, Object> instances             = new ConcurrentIdentityHashMap<DruidDataSource, Object>();

    private static final long                                               serialVersionUID      = 1L;

    private final ReentrantLock                                             lock                  = new ReentrantLock();

    private final Condition                                                 notEmpty              = lock.newCondition();
    private final Condition                                                 notMaxActive          = lock.newCondition();
    private final Condition                                                 lowWater              = lock.newCondition();

    // stats
    private long                                                            connectCount          = 0L;
    private long                                                            closeCount            = 0L;
    private long                                                            connectErrorCount     = 0L;
    private long                                                            recycleCount          = 0L;
    private long                                                            createConnectionCount = 0L;
    private long                                                            destroyCount          = 0L;

    // store
    private ConnectionHolder[]                                              connections;
    private int                                                             poolingCount          = 0;
    private int                                                             activeCount           = 0;

    // threads
    private CreateConnectionThread                                          createConnectionThread;
    private DestroyConnectionThread                                         destoryConnectionThread;

    private final CountDownLatch                                            initedLatch           = new CountDownLatch(2);

    private long                                                            id;
    private Date                                                            createdTime;

    private boolean                                                         enable                = true;

    public DruidDataSource(){
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    private void init() throws SQLException {
        if (inited) {
            return;
        }

        lock.lock();
        try {
            if (inited) {
                return;
            }

            this.id = DruidDriver.createDataSourceId();

            if (maxActive <= 0) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (maxIdle <= 0 || maxIdle < minIdle) {
                throw new IllegalArgumentException("illegal maxPoolSize");
            }

            if (this.driverClass != null) {
                this.driverClass = driverClass.trim();
            }

            if (this.jdbcUrl != null) {
                this.jdbcUrl = this.jdbcUrl.trim();
            }

            if (this.driver == null) {
                if (this.driverClass == null || this.driverClass.isEmpty()) {
                    this.driverClass = JdbcUtils.getDriverClassName(this.jdbcUrl);
                }

                try {
                    driver = (Driver) Class.forName(this.driverClass).newInstance();
                } catch (IllegalAccessException e) {
                    throw new SQLException(e.getMessage(), e);
                } catch (InstantiationException e) {
                    throw new SQLException(e.getMessage(), e);
                } catch (ClassNotFoundException e) {
                    throw new SQLException(e.getMessage(), e);
                }
            } else {
                if (this.driverClass == null) {
                    this.driverClass = driver.getClass().getName();
                }
            }

            this.dbType = JdbcUtils.getDbType(jdbcUrl, driverClass.getClass().getName());

            String realDriverClassName = driver.getClass().getName();
            if (realDriverClassName.equals("com.mysql.jdbc.Driver")) {
                this.validConnectionChecker = new MySqlValidConnectionChecker();
                this.exceptionSoter = new MySqlExceptionSorter();

            } else if (realDriverClassName.equals("oracle.jdbc.driver.OracleDriver")) {
                this.validConnectionChecker = new OracleValidConnectionChecker();
                this.exceptionSoter = new OracleExceptionSorter();

            } else if (realDriverClassName.equals("com.microsoft.jdbc.sqlserver.SQLServerDriver")) {
                this.validConnectionChecker = new MSSQLValidConnectionChecker();

            } else if (realDriverClassName.equals("com.informix.jdbc.IfxDriver")) {
                this.exceptionSoter = new InformixExceptionSorter();

            } else if (realDriverClassName.equals("com.sybase.jdbc2.jdbc.SybDriver")) {
                this.exceptionSoter = new SybaseExceptionSorter();
            }

            for (Filter filter : filters) {
                filter.init(this);
            }

            initConnectionFactory();

            int capacity = maxIdle + maxActive;

            if (capacity < getInitialSize()) {
                capacity = getInitialSize();
            }

            connections = new ConnectionHolder[capacity];

            SQLException connectError = null;

            try {
                // 初始化连接
                for (int i = 0, size = getInitialSize(); i < size; ++i) {
                    Connection conn = connectionFactory.createConnection();
                    conn.setAutoCommit(true);
                    connections[poolingCount++] = new ConnectionHolder(this, conn);
                }
            } catch (SQLException ex) {
                LOG.error("init datasource error", ex);
                connectError = ex;
            }

            createConnectionThread = new CreateConnectionThread("Druid-ConnectionPool-Create");
            createConnectionThread.setDaemon(true);
            destoryConnectionThread = new DestroyConnectionThread("Druid-ConnectionPool-Destory");
            destoryConnectionThread.setDaemon(true);

            createConnectionThread.start();
            destoryConnectionThread.start();

            initedLatch.await();
            inited = true;

            createdTime = new Date();
            instances.put(this, PRESENT);

            if (connectError != null && poolingCount == 0) {
                throw connectError;
            }
        } catch (InterruptedException e) {
            throw new SQLException(e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        init();

        final int maxWaitThreadCount = getMaxWaitThreadCount();
        if (maxWaitThreadCount > 0) {
            if (lock.getQueueLength() > maxWaitThreadCount) {
                throw new SQLException("maxWaitThreadCount " + maxWaitThreadCount + ", current wait Thread count "
                                       + lock.getQueueLength());
            }
        }

        for (;;) {
            PoolableConnection poolalbeConnection = getConnectionInternal();

            if (isTestOnBorrow()) {
                boolean validate = testConnectionInternal(poolalbeConnection.getConnection());
                if (!validate) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("skip not validate connection.");
                    }

                    Connection realConnection = poolalbeConnection.getConnection();
                    JdbcUtils.close(realConnection);
                    this.decrementActiveCountWithLock();
                    continue;
                }
                poolalbeConnection.getConnectionHolder().setLastCheckTimeMillis(System.currentTimeMillis());
            } else {
                Connection realConnection = poolalbeConnection.getConnection();
                if (realConnection.isClosed()) {
                    JdbcUtils.close(realConnection);
                    this.decrementActiveCountWithLock();
                    continue;
                }

                if (isTestWhileIdle()) {
                    long idleMillis = System.currentTimeMillis()
                                      - poolalbeConnection.getConnectionHolder().getLastActiveTimeMillis();
                    if (idleMillis >= this.getTimeBetweenEvictionRunsMillis()) {
                        boolean validate = testConnectionInternal(poolalbeConnection.getConnection());
                        if (!validate) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("skip not validate connection.");
                            }

                            JdbcUtils.close(realConnection);
                            this.decrementActiveCountWithLock();
                            continue;
                        }
                        poolalbeConnection.getConnectionHolder().setLastCheckTimeMillis(System.currentTimeMillis());
                    }
                }
            }

            if (isRemoveAbandoned()) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                activeConnections.put(poolalbeConnection,
                                      new ActiveConnectionTraceInfo(poolalbeConnection, System.currentTimeMillis(),
                                                                    stackTrace));
            }

            return poolalbeConnection;
        }
    }

    private PoolableConnection getConnectionInternal() throws SQLException {
        PoolableConnection poolalbeConnection;

        lock.lock();
        try {
            if (!enable) {
                connectErrorCount++;
                throw new DataSourceDisableException();
            }
            
            connectCount++;

            ConnectionHolder holder;

            if (maxWait > 0) {
                holder = pollLast(maxWait, TimeUnit.MILLISECONDS);
            } else {
                holder = takeLast();
            }

            if (holder == null) {
                throw new SQLException("can not get connection");
            }

            holder.incrementUseCount();
            activeCount++;

            poolalbeConnection = new PoolableConnection(holder);
        } catch (InterruptedException e) {
            connectErrorCount++;
            throw new SQLException(e.getMessage(), e);
        } catch (SQLException e) {
            connectErrorCount++;
            throw e;
        } finally {
            lock.unlock();
        }
        return poolalbeConnection;
    }

    /**
     * 回收连接
     */
    protected void recycle(PoolableConnection pooledConnection) throws SQLException {
        final Connection conn = pooledConnection.getConnection();
        final ConnectionHolder holder = pooledConnection.getConnectionHolder();

        assert holder != null;

        try {
            // 第一步，检查连接是否关闭
            if (conn == null || conn.isClosed()) {
                lock.lock();
                try {
                    decrementActiveCount();
                    closeCount++;
                } finally {
                    lock.unlock();
                }
                return;
            }

            final boolean isAutoCommit = conn.getAutoCommit();
            final boolean isReadOnly = conn.isReadOnly();

            // 第二步，检查是否需要回滚
            if ((!isAutoCommit) && (!isReadOnly)) {
                conn.rollback();
            }

            // 第三步，清楚警告信息，重设autoCommit为true
            conn.clearWarnings();
            if (!isAutoCommit) {
                conn.setAutoCommit(true);
            }

            // 第四步，检查是符合MaxIdle的设定
            if (poolingCount >= maxIdle) {
                lock.lock();
                try {
                    JdbcUtils.close(conn);
                    destroyCount++;
                    closeCount++;
                    decrementActiveCount();
                } finally {
                    lock.unlock();
                }
                return;
            }

            //
            if (isTestOnReturn()) {
                boolean validate = testConnectionInternal(conn);
                if (!validate) {
                    lock.lock();
                    try {
                        JdbcUtils.close(conn);
                        destroyCount++;
                        closeCount++;
                        decrementActiveCount();
                    } finally {
                        lock.unlock();
                    }
                    return;
                }
                holder.setLastCheckTimeMillis(System.currentTimeMillis());
            }

            lock.lock();
            try {
                decrementActiveCount();
                closeCount++;

                // 第六步，加入队列中(putLast)
                putLast(holder);
                recycleCount++;
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            if (!conn.isClosed()) {
                conn.close();
            }

            throw new SQLException(e.getMessage(), e);
        } catch (SQLException e) {
            lock.lock();
            try {
                decrementActiveCount();
                closeCount++;
            } finally {
                lock.unlock();
            }

            throw e;
        }
    }

    /**
     * close datasource
     */
    public void close() {
        lock.lock();
        try {
            if (createConnectionThread != null) {
                createConnectionThread.interrupt();
            }

            if (destoryConnectionThread != null) {
                destoryConnectionThread.interrupt();
            }

            for (int i = 0; i < poolingCount; ++i) {
                try {
                    connections[i].getConnection().close();
                    connections[i] = null;
                    destroyCount++;
                } catch (Exception ex) {
                    LOG.warn("close connection error", ex);
                }
            }
            poolingCount = 0;
            instances.remove(this);
        } finally {
            lock.unlock();
        }
    }

    void incrementCreateCount() {
        createConnectionCount++;
    }

    void decrementActiveCount() {
        activeCount--;
        notMaxActive.signal();
    }

    void decrementActiveCountWithLock() {
        lock.lock();
        try {
            decrementActiveCount();
        } finally {
            lock.unlock();
        }
    }

    void putLast(ConnectionHolder e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }

        e.setLastActiveTimeMillis(System.currentTimeMillis());
        connections[poolingCount++] = e;

        notEmpty.signal();
    }

    ConnectionHolder takeLast() throws InterruptedException {
        while (activeCount >= maxActive) {
            try {
                notMaxActive.await(); // signal by recycle
            } catch (InterruptedException ie) {
                notMaxActive.signal(); // propagate to non-interrupted thread
                throw ie;
            }
        }

        try {
            while (poolingCount == 0) {
                lowWater.signal(); // send signal to CreateThread create connection
                notEmpty.await(); // signal by recycle or creator
            }
        } catch (InterruptedException ie) {
            notEmpty.signal(); // propagate to non-interrupted thread
            throw ie;
        }

        poolingCount--;
        ConnectionHolder last = connections[poolingCount];
        connections[poolingCount] = null;

        return last;
    }

    ConnectionHolder pollLast(long timeout, TimeUnit unit) throws InterruptedException {
        long estimate = unit.toNanos(timeout);

        for (;;) {
            while (activeCount >= maxActive) {
                if (estimate <= 0) {
                    return null;
                }
                
                try {
                    estimate = notMaxActive.awaitNanos(estimate); // signal by recycle
                } catch (InterruptedException ie) {
                    notMaxActive.signal(); // propagate to non-interrupted thread
                    throw ie;
                }
            }

            if (poolingCount == 0) {
                lowWater.signal(); // send signal to CreateThread create connection
                
                if (estimate <= 0) {
                    return null;
                }
                
                try {
                    estimate = notEmpty.awaitNanos(estimate); // signal by recycle or creator
                } catch (InterruptedException ie) {
                    notEmpty.signal(); // propagate to non-interrupted thread
                    throw ie;
                }
                
                if (poolingCount == 0) {
                    if (estimate > 0) {
                        continue;
                    }

                    return null;
                }
            }

            poolingCount--;
            ConnectionHolder last = connections[poolingCount];
            connections[poolingCount] = null;

            return last;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new UnsupportedOperationException("Not supported by DruidDataSource");
    }

    public long getCreateCount() {
        return createConnectionCount;
    }

    public long getDestroyCount() {
        return destroyCount;
    }

    public long getConnectCount() {
        return connectCount;
    }

    public long getCloseCount() {
        return closeCount;
    }

    public long getConnectErrorCount() {
        return connectErrorCount;
    }

    @Override
    public int getPoolingCount() {
        return poolingCount;
    }

    public long getRecycleCount() {
        return recycleCount;
    }

    public int getActiveCount() {
        return activeCount;
    }

    public class CreateConnectionThread extends Thread {

        public CreateConnectionThread(String name){
            super(name);
        }

        public void run() {
            initedLatch.countDown();

            final int MAX_ERRRO_TRY = 3;
            int errorCount = 0;
            for (;;) {
                // addLast
                lock.lock();
                try {
                    // 必须存在线程等待，才创建连接
                    if (lock.getWaitQueueLength(notEmpty) == 0) {
                        lowWater.await();
                        continue;
                    }
                    
                    if (minIdle > 0 && poolingCount >= minIdle) {
                        lowWater.await();
                        continue;
                    }

                    // 防止创建超过maxActive数量的连接
                    int max = maxActive > maxIdle ? maxActive : maxIdle;
                    if (activeCount + poolingCount >= max) {
                        lowWater.await();
                        continue;
                    }

                    Connection connection = connectionFactory.createConnection();
                    ConnectionHolder poolableConnection = new ConnectionHolder(DruidDataSource.this, connection);
                    connections[poolingCount++] = poolableConnection;

                    errorCount = 0; // reset errorCount

                    notEmpty.signal();

                } catch (InterruptedException e) {
                    break;
                } catch (SQLException e) {
                    LOG.error("create connection error", e);

                    errorCount++;

                    if (errorCount > MAX_ERRRO_TRY && timeBetweenConnectErrorMillis > 0) {
                        try {
                            Thread.sleep(timeBetweenConnectErrorMillis);
                        } catch (InterruptedException e1) {
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.error("create connection error", e);
                } catch (Error e) {
                    LOG.error("create connection error", e);
                    break;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    public class DestroyConnectionThread extends Thread {

        public DestroyConnectionThread(String name){
            super(name);
        }

        public void run() {
            initedLatch.countDown();

            final List<ConnectionHolder> evictList = new ArrayList<ConnectionHolder>();
            FOR_0: for (;;) {
                // 从前面开始删除
                try {
                    if (timeBetweenEvictionRunsMillis > 0) {
                        Thread.sleep(timeBetweenEvictionRunsMillis);
                    } else {
                        Thread.sleep(1000); //
                    }

                    if (Thread.interrupted()) {
                        break;
                    }

                    if (poolingCount <= 0) {
                        Thread.sleep(minEvictableIdleTimeMillis);
                        continue;
                    }

                    lock.lock();
                    try {
                        int numTestsPerEvictionRun = DruidDataSource.this.numTestsPerEvictionRun;
                        if (numTestsPerEvictionRun <= 0) {
                            numTestsPerEvictionRun = 1;
                        }

                        for (int i = 0; i < poolingCount; ++i) {
                            ConnectionHolder connection = connections[i];

                            if (connection == null) {
                                continue FOR_0;
                            }

                            if (poolingCount - evictList.size() <= minIdle) {
                                break;
                            }

                            long idleMillis = System.currentTimeMillis() - connection.getLastActiveTimeMillis();
                            if (idleMillis >= minEvictableIdleTimeMillis) {
                                evictList.add(connection);
                            }
                        }
                        int removeCount = evictList.size();
                        if (removeCount > 0) {
                            System.arraycopy(connections, removeCount, connections, 0, poolingCount - removeCount);
                            Arrays.fill(connections, poolingCount - removeCount, poolingCount, null);
                            poolingCount -= removeCount;
                        }
                    } finally {
                        lock.unlock();
                    }

                    for (ConnectionHolder item : evictList) {
                        Connection connection = item.getConnection();
                        JdbcUtils.close(connection);
                        destroyCount++;
                    }

                    if (evictList.size() > 0) {
                        if (LOG.isDebugEnabled()) {
                            StringBuilder buf = new StringBuilder();
                            buf.append("evict ");
                            buf.append(evictList.size());
                            buf.append(", [");
                            for (int i = 0; i < evictList.size(); ++i) {
                                if (i != 0) {
                                    buf.append(",");
                                }
                                buf.append(System.identityHashCode(evictList.get(i)));
                            }
                            buf.append("]");
                            LOG.debug(buf.toString());
                        }
                    }

                    evictList.clear();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    public DataSourceProxyConfig getConfig() {
        return null;
    }

    /** Instance key */
    protected String instanceKey = null;

    public Reference getReference() throws NamingException {
        final String className = getClass().getName();
        final String factoryName = className + "Factory"; // XXX: not robust
        Reference ref = new Reference(className, factoryName, null);
        ref.add(new StringRefAddr("instanceKey", instanceKey));
        return ref;
    }

    static class ActiveConnectionTraceInfo {

        private final PoolableConnection  connection;
        private final long                connectTime;
        private final StackTraceElement[] stackTrace;

        public ActiveConnectionTraceInfo(PoolableConnection connection, long connectTime, StackTraceElement[] stackTrace){
            super();
            this.connection = connection;
            this.connectTime = connectTime;
            this.stackTrace = stackTrace;
        }

        public PoolableConnection getConnection() {
            return connection;
        }

        public long getConnectTime() {
            return connectTime;
        }

        public StackTraceElement[] getStackTrace() {
            return stackTrace;
        }
    }

    @Override
    public List<String> getFilterClassNames() {
        List<String> names = new ArrayList<String>();
        for (Filter filter : filters) {
            names.add(filter.getClass().getName());
        }
        return names;
    }

    public static Set<DruidDataSource> getInstances() {
        return instances.keySet();
    }

    public long getID() {
        return this.id;
    }

    private int getRawDriverMajorVersion() {
        int version = -1;
        if (this.driver != null) {
            version = driver.getMajorVersion();
        }
        return version;
    }

    private int getRawDriverMinorVersion() {
        int version = -1;
        if (this.driver != null) {
            version = driver.getMinorVersion();
        }
        return version;
    }

    private String getProperties() {
        if (this.connectionProperties == null) {
            return null;
        }

        Properties properties = new Properties(this.connectionProperties);
        if (properties.contains("password")) {
            properties.put("password", "******");
        }
        return properties.toString();
    }

    public String[] getFilterClasses() {
        List<Filter> filterConfigList = getProxyFilters();

        List<String> classes = new ArrayList<String>();
        for (Filter filter : filterConfigList) {
            classes.add(filter.getClass().getName());
        }

        return classes.toArray(new String[classes.size()]);
    }

    public CompositeDataSupport getCompositeData() throws JMException {
        StatFilter statFilter = null;
        JdbcDataSourceStat stat = null;
        for (Filter filter : this.getProxyFilters()) {
            if (filter instanceof StatFilter) {
                statFilter = (StatFilter) filter;
            }
        }
        if (statFilter != null) {
            stat = statFilter.getDataSourceStat();
        }

        Map<String, Object> map = new HashMap<String, Object>();

        map.put("ID", id);
        map.put("URL", this.getUrl());
        map.put("Name", this.getName());
        map.put("FilterClasses", getFilterClasses());
        map.put("CreatedTime", getCreatedTime());

        map.put("RawDriverClassName", getDriverClassName());
        map.put("RawUrl", getUrl());
        map.put("RawDriverMajorVersion", getRawDriverMajorVersion());
        map.put("RawDriverMinorVersion", getRawDriverMinorVersion());
        map.put("Properties", getProperties());

        if (stat != null) {
            map.put("ConnectionActiveCount", stat.getConnectionActiveCount());
            map.put("ConnectionActiveCountMax", stat.getConnectionStat().getActiveMax());
            map.put("ConnectionCloseCount", stat.getConnectionStat().getCloseCount());
            map.put("ConnectionCommitCount", stat.getConnectionStat().getCommitCount());
            map.put("ConnectionRollbackCount", stat.getConnectionStat().getRollbackCount());

            map.put("ConnectionConnectLastTime", stat.getConnectionStat().getConnectLastTime());
            map.put("ConnectionConnectErrorCount", stat.getConnectionStat().getConnectErrorCount());
            Throwable lastConnectionConnectError = stat.getConnectionStat().getConnectErrorLast();
            if (lastConnectionConnectError != null) {
                map.put("ConnectionConnectErrorLastTime", stat.getConnectionStat().getErrorLastTime());
                map.put("ConnectionConnectErrorLastMessage", lastConnectionConnectError.getMessage());
                map.put("ConnectionConnectErrorLastStackTrace", JdbcUtils.getStackTrace(lastConnectionConnectError));
            } else {
                map.put("ConnectionConnectErrorLastTime", null);
                map.put("ConnectionConnectErrorLastMessage", null);
                map.put("ConnectionConnectErrorLastStackTrace", null);
            }

            map.put("StatementCreateCount", stat.getStatementStat().getCreateCount());
            map.put("StatementPrepareCount", stat.getStatementStat().getPrepareCount());
            map.put("StatementPreCallCount", stat.getStatementStat().getPrepareCallCount());
            map.put("StatementExecuteCount", stat.getStatementStat().getExecuteCount());
            map.put("StatementRunningCount", stat.getStatementStat().getRunningCount());

            map.put("StatementConcurrentMax", stat.getStatementStat().getConcurrentMax());
            map.put("StatementCloseCount", stat.getStatementStat().getCloseCount());
            map.put("StatementErrorCount", stat.getStatementStat().getErrorCount());
            Throwable lastStatementError = stat.getStatementStat().getLastException();
            if (lastStatementError != null) {
                map.put("StatementLastErrorTime", stat.getStatementStat().getLastErrorTime());
                map.put("StatementLastErrorMessage", lastStatementError.getMessage());

                map.put("StatementLastErrorStackTrace", JdbcUtils.getStackTrace(lastStatementError));
            } else {
                map.put("StatementLastErrorTime", null);
                map.put("StatementLastErrorMessage", null);

                map.put("StatementLastErrorStackTrace", null);
            }
            map.put("StatementExecuteMillisTotal", stat.getStatementStat().getMillisTotal());
            map.put("StatementExecuteLastTime", stat.getStatementStat().getExecuteLastTime());
            map.put("ConnectionConnectingCount", stat.getConnectionStat().getConnectingCount());
            map.put("ResultSetCloseCount", stat.getResultSetStat().getCloseCount());

            map.put("ResultSetOpenCount", stat.getResultSetStat().getOpenCount());
            map.put("ResultSetOpenningCount", stat.getResultSetStat().getOpenningCount());
            map.put("ResultSetOpenningMax", stat.getResultSetStat().getOpenningMax());
            map.put("ResultSetFetchRowCount", stat.getResultSetStat().getFetchRowCount());
            map.put("ResultSetLastOpenTime", stat.getResultSetStat().getLastOpenTime());

            map.put("ResultSetErrorCount", stat.getResultSetStat().getErrorCount());
            map.put("ResultSetOpenningMillisTotal", stat.getResultSetStat().getAliveMillisTotal());
            map.put("ResultSetLastErrorTime", stat.getResultSetStat().getLastErrorTime());
            Throwable lastResultSetError = stat.getResultSetStat().getLastError();
            if (lastResultSetError != null) {
                map.put("ResultSetLastErrorMessage", lastResultSetError.getMessage());
                map.put("ResultSetLastErrorStackTrace", JdbcUtils.getStackTrace(lastResultSetError));
            } else {
                map.put("ResultSetLastErrorMessage", null);
                map.put("ResultSetLastErrorStackTrace", null);
            }

            map.put("ConnectionConnectCount", stat.getConnectionStat().getConnectCount());
            Throwable lastConnectionError = stat.getConnectionStat().getErrorLast();
            if (lastConnectionError != null) {
                map.put("ConnectionErrorLastMessage", lastConnectionError.getMessage());
                map.put("ConnectionErrorLastStackTrace", JdbcUtils.getStackTrace(lastConnectionError));
            } else {
                map.put("ConnectionErrorLastMessage", null);
                map.put("ConnectionErrorLastStackTrace", null);
            }
            map.put("ConnectionConnectMillisTotal", stat.getConnectionStat().getConnectMillis());
            map.put("ConnectionConnectingCountMax", stat.getConnectionStat().getConnectingMax());

            map.put("ConnectionConnectMillisMax", stat.getConnectionStat().getConnectMillisMax());
            map.put("ConnectionErrorLastTime", stat.getConnectionStat().getErrorLastTime());
            map.put("ConnectionAliveMillisMax", stat.getConnectionConnectAliveMillisMax());
            map.put("ConnectionAliveMillisMin", stat.getConnectionConnectAliveMillisMin());

            map.put("ConnectionCount_Alive_0_1_Seconds", stat.getConnectionCount_Alive_0_1_Seconds());
            map.put("ConnectionCount_Alive_1_5_Seconds", stat.getConnectionCount_Alive_1_5_Seconds());
            map.put("ConnectionCount_Alive_5_10_Seconds", stat.getConnectionCount_Alive_5_10_Seconds());
            map.put("ConnectionCount_Alive_10_30_Seconds", stat.getConnectionCount_Alive_10_30_Seconds());
            map.put("ConnectionCount_Alive_30_60_Seconds", stat.getConnectionCount_Alive_30_60_Seconds());

            map.put("ConnectionCount_Alive_1_5_Minutes", stat.getConnectionCount_Alive_1_5_Minutes());
            map.put("ConnectionCount_Alive_5_10_Minutes", stat.getConnectionCount_Alive_5_10_Minutes());
            map.put("ConnectionCount_Alive_10_30_Minutes", stat.getConnectionCount_Alive_10_30_Minutes());
            map.put("ConnectionCount_Alive_30_60_Minutes", stat.getConnectionCount_Alive_30_60_Minutes());
            map.put("ConnectionCount_Alive_1_6_Hours", stat.getConnectionCount_Alive_1_6_Hours());

            map.put("ConnectionCount_Alive_6_24_Hours", stat.getConnectionCount_Alive_6_24_Hours());
            map.put("ConnectionCount_Alive_1_7_Day", stat.getConnectionCount_Alive_1_7_day());
            map.put("ConnectionCount_Alive_7_30_Day", stat.getConnectionCount_Alive_7_30_Day());
            map.put("ConnectionCount_Alive_30_90_Day", stat.getConnectionCount_Alive_30_90_Day());
            map.put("ConnectionCount_Alive_90_more_Day", stat.getConnectionCount_Alive_90_more_Day());

            map.put("StatementExecuteCount_0_1_Millis", stat.getStatementStat().getCount_0_1_Millis());
            map.put("StatementExecuteCount_1_2_Millis", stat.getStatementStat().getCount_1_2_Millis());
            map.put("StatementExecuteCount_2_5_Millis", stat.getStatementStat().getCount_2_5_Millis());
            map.put("StatementExecuteCount_5_10_Millis", stat.getStatementStat().getCount_5_10_Millis());
            map.put("StatementExecuteCount_10_20_Millis", stat.getStatementStat().getCount_10_20_Millis());

            map.put("StatementExecuteCount_20_50_Millis", stat.getStatementStat().getCount_20_50_Millis());
            map.put("StatementExecuteCount_50_100_Millis", stat.getStatementStat().getCount_50_100_Millis());
            map.put("StatementExecuteCount_100_200_Millis", stat.getStatementStat().getCount_100_200_Millis());
            map.put("StatementExecuteCount_200_500_Millis", stat.getStatementStat().getCount_200_500_Millis());
            map.put("StatementExecuteCount_500_1000_Millis", stat.getStatementStat().getCount_500_1000_Millis());

            map.put("StatementExecuteCount_1_2_Seconds", stat.getStatementStat().getCount_1_2_Seconds());
            map.put("StatementExecuteCount_2_5_Seconds", stat.getStatementStat().getCount_2_5_Seconds());
            map.put("StatementExecuteCount_5_10_Seconds", stat.getStatementStat().getCount_5_10_Seconds());
            map.put("StatementExecuteCount_10_30_Seconds", stat.getStatementStat().getCount_10_30_Seconds());
            map.put("StatementExecuteCount_30_60_Seconds", stat.getStatementStat().getCount_30_60_Seconds());

            map.put("StatementExecuteCount_1_2_Minutes", stat.getStatementStat().getCount_1_2_minutes());
            map.put("StatementExecuteCount_2_5_Minutes", stat.getStatementStat().getCount_2_5_minutes());
            map.put("StatementExecuteCount_5_10_Minutes", stat.getStatementStat().getCount_5_10_minutes());
            map.put("StatementExecuteCount_10_30_Minutes", stat.getStatementStat().getCount_10_30_minutes());
            map.put("StatementExecuteCount_30_more_Minutes", stat.getStatementStat().getCount_30_more_minutes());
        } else {
            map.put("ConnectionActiveCount", null);
            map.put("ConnectionActiveCountMax", null);
            map.put("ConnectionCloseCount", null);
            map.put("ConnectionCommitCount", null);
            map.put("ConnectionRollbackCount", null);

            map.put("ConnectionConnectLastTime", null);
            map.put("ConnectionConnectErrorCount", null);
            map.put("ConnectionConnectErrorLastTime", null);
            map.put("ConnectionConnectErrorLastMessage", null);
            map.put("ConnectionConnectErrorLastStackTrace", null);

            map.put("StatementCreateCount", null);
            map.put("StatementPrepareCount", null);
            map.put("StatementPreCallCount", null);
            map.put("StatementExecuteCount", null);
            map.put("StatementRunningCount", null);

            map.put("StatementConcurrentMax", null);
            map.put("StatementCloseCount", null);
            map.put("StatementErrorCount", null);
            map.put("StatementLastErrorTime", null);
            map.put("StatementLastErrorMessage", null);

            map.put("StatementLastErrorStackTrace", null);
            map.put("StatementExecuteMillisTotal", null);
            map.put("ConnectionConnectingCount", null);
            map.put("StatementExecuteLastTime", null);
            map.put("ResultSetCloseCount", null);

            map.put("ResultSetOpenCount", null);
            map.put("ResultSetOpenningCount", null);
            map.put("ResultSetOpenningMax", null);
            map.put("ResultSetFetchRowCount", null);
            map.put("ResultSetLastOpenTime", null);

            map.put("ResultSetLastErrorCount", null);
            map.put("ResultSetOpenningMillisTotal", null);
            map.put("ResultSetLastErrorTime", null);
            map.put("ResultSetLastErrorMessage", null);
            map.put("ResultSetLastErrorStackTrace", null);

            map.put("ConnectionConnectCount", null);
            map.put("ConnectionErrorLastMessage", null);
            map.put("ConnectionErrorLastStackTrace", null);
            map.put("ConnectionConnectMillisTotal", null);
            map.put("ConnectionConnectingCountMax", null);

            map.put("ConnectionConnectMillisMax", null);
            map.put("ConnectionErrorLastTime", null);
            map.put("ConnectionAliveMillisMax", null);
            map.put("ConnectionAliveMillisMin", null);

            map.put("ConnectionCount_Alive_0_1_Seconds", null);
            map.put("ConnectionCount_Alive_1_5_Seconds", null);
            map.put("ConnectionCount_Alive_5_10_Seconds", null);
            map.put("ConnectionCount_Alive_10_30_Seconds", null);
            map.put("ConnectionCount_Alive_30_60_Seconds", null);

            map.put("ConnectionCount_Alive_1_5_Minutes", null);
            map.put("ConnectionCount_Alive_5_10_Minutes", null);
            map.put("ConnectionCount_Alive_10_30_Minutes", null);
            map.put("ConnectionCount_Alive_30_60_Minutes", null);
            map.put("ConnectionCount_Alive_1_6_Hours", null);

            map.put("ConnectionCount_Alive_6_24_Hours", null);
            map.put("ConnectionCount_Alive_1_7_Day", null);
            map.put("ConnectionCount_Alive_7_30_Day", null);
            map.put("ConnectionCount_Alive_30_90_Day", null);
            map.put("ConnectionCount_Alive_90_more_Day", null);

            map.put("StatementExecuteCount_0_1_Millis", null);
            map.put("StatementExecuteCount_1_2_Millis", null);
            map.put("StatementExecuteCount_2_5_Millis", null);
            map.put("StatementExecuteCount_5_10_Millis", null);
            map.put("StatementExecuteCount_10_20_Millis", null);

            map.put("StatementExecuteCount_20_50_Millis", null);
            map.put("StatementExecuteCount_50_100_Millis", null);
            map.put("StatementExecuteCount_100_200_Millis", null);
            map.put("StatementExecuteCount_200_500_Millis", null);
            map.put("StatementExecuteCount_500_1000_Millis", null);

            map.put("StatementExecuteCount_1_2_Seconds", null);
            map.put("StatementExecuteCount_2_5_Seconds", null);
            map.put("StatementExecuteCount_5_10_Seconds", null);
            map.put("StatementExecuteCount_10_30_Seconds", null);
            map.put("StatementExecuteCount_30_60_Seconds", null);

            map.put("StatementExecuteCount_1_2_Minutes", null);
            map.put("StatementExecuteCount_2_5_Minutes", null);
            map.put("StatementExecuteCount_5_10_Minutes", null);
            map.put("StatementExecuteCount_10_30_Minutes", null);
            map.put("StatementExecuteCount_30_more_Minutes", null);
        }

        return new CompositeDataSupport(JdbcStatManager.getDataSourceCompositeType(), map);
    }

    @Override
    public void shrink() {
        final List<ConnectionHolder> evictList = new ArrayList<ConnectionHolder>();
        lock.lock();
        try {
            for (int i = 0; i < poolingCount; ++i) {
                ConnectionHolder connection = connections[i];

                if (poolingCount - evictList.size() <= minIdle) {
                    break;
                }

                evictList.add(connection);
            }
            int removeCount = evictList.size();
            if (removeCount > 0) {
                System.arraycopy(connections, removeCount, connections, 0, poolingCount - removeCount);
                Arrays.fill(connections, poolingCount - removeCount, poolingCount, null);
                poolingCount -= removeCount;
            }
        } finally {
            lock.unlock();
        }

        for (ConnectionHolder item : evictList) {
            Connection connection = item.getConnection();
            JdbcUtils.close(connection);
            destroyCount++;
        }
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();

        buf.append("{");

        buf.append("\n\tCreateTime:\"");
        buf.append(JdbcUtils.toString(getCreatedTime()));
        buf.append("\"");

        buf.append(",\n\tActiveCount:");
        buf.append(getActiveCount());

        buf.append(",\n\tPoolingCount:");
        buf.append(getPoolingCount());

        buf.append(",\n\tCreateCount:");
        buf.append(getCreateCount());

        buf.append(",\n\tDestroyCount:");
        buf.append(getDestroyCount());

        buf.append(",\n\tCloseCount:");
        buf.append(getCloseCount());

        buf.append(",\n\tConnectCount:");
        buf.append(getConnectCount());

        buf.append(",\n\tConnections:[");
        for (int i = 0; i < poolingCount; ++i) {
            ConnectionHolder conn = connections[i];
            if (conn != null) {
                if (i != 0) {
                    buf.append(",");
                }
                buf.append("\n\t\t");
                buf.append(conn.toString());
            }
        }
        buf.append("\n\t]");

        buf.append("\n}");

        return buf.toString();
    }
}
