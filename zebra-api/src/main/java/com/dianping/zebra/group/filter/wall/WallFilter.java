package com.dianping.zebra.group.filter.wall;

import com.dianping.avatar.tracker.ExecutionContextHolder;
import com.dianping.zebra.group.filter.DefaultJdbcFilter;
import com.dianping.zebra.group.filter.JdbcMetaData;
import com.dianping.zebra.group.filter.delegate.FilterFunction;
import com.dianping.zebra.group.filter.delegate.FilterFunctionWithSQLException;
import com.dianping.zebra.group.util.StringUtils;
import jodd.cache.LRUCache;

import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Dozer on 9/24/14.
 */
public class WallFilter extends DefaultJdbcFilter {
    protected final static Pattern ID_PATTERN = Pattern.compile(".*(\\/\\*z:)([a-zA-Z0-9]{10})(\\*\\/).*");
    private static final int MAX_ID_LENGTH = 8;
    private static final String SQL_STATEMENT_NAME = "sql_statement_name";
    private static final LRUCache<String, String> sqlIdCache = new LRUCache<String, String>(1024, 60 * 60);

    protected String addIdToSql(String sql, JdbcMetaData metaData) {
        try {
            return String.format("/*z:%s*/%s", generateId(metaData), sql);
        } catch (NoSuchAlgorithmException e) {
            return sql;
        }
    }

    protected void analyticsExecute(JdbcMetaData sql, long executeTime) {

    }

    @Override
    public <S, T> T execute(JdbcMetaData metaData, S source, FilterFunctionWithSQLException<S, T> action)
            throws SQLException {
        findLongSqlString(metaData);
        long executeStart = System.currentTimeMillis();
        T result = super.execute(metaData, source, action);
        long executeTime = System.currentTimeMillis() - executeStart;
        analyticsExecute(metaData, executeTime);
        return result;
    }

    protected void findLongSqlString(JdbcMetaData metaData) throws SQLException {
    }

    protected String generateId(JdbcMetaData metaData) throws NoSuchAlgorithmException {
        String token = ExecutionContextHolder.getContext().get(SQL_STATEMENT_NAME);
        if (StringUtils.isBlank(token)) {
            token = metaData.getSql();
        }

        if (StringUtils.isBlank(token)) {
            return null;
        }

        if (metaData.getRealJdbcMetaData() != null && metaData.getRealJdbcMetaData().getDataSourceId() != null) {
            token = String.format("/*%s*/%s", metaData.getRealJdbcMetaData().getDataSourceId(), token);
        }

        String sqlId = sqlIdCache.get(token);

        if (sqlId != null) {
            return sqlId;
        }

        sqlId = StringUtils.sha1(token).substring(0, MAX_ID_LENGTH);

        sqlIdCache.put(token, sqlId);

        return sqlId;
    }

    protected String getIdFromSQL(String sql) {
        Matcher matcher = ID_PATTERN.matcher(sql);
        if (matcher.matches()) {
            return matcher.group(2);
        } else {
            return null;
        }
    }

    public int getOrder() {
        return MIN_ORDER;
    }

    @Override
    public <S> String sql(JdbcMetaData metaData, S source, FilterFunction<S, String> action) {
        String result = super.sql(metaData, source, action);
        result = addIdToSql(result, metaData);
        return result;
    }
}