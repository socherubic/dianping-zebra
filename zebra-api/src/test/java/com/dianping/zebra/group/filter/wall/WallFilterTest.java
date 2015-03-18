package com.dianping.zebra.group.filter.wall;

/**
 * Created by Dozer on 9/24/14.
 */

import java.sql.SQLException;

import junit.framework.Assert;

import org.junit.Test;

import com.dianping.avatar.tracker.ExecutionContextHolder;
import com.dianping.zebra.group.config.datasource.entity.DataSourceConfig;
import com.dianping.zebra.group.datasources.SingleConnection;
import com.dianping.zebra.group.filter.JdbcFilter;
import com.google.common.collect.Lists;

public class WallFilterTest {
	@Test
	public void test_addId_to_Sql() throws SQLException {
		WallFilter filter = new MockWallFilter(0);
		filter.setConfigManagerType("local");
		filter.init();
		
		DataSourceConfig config = new DataSourceConfig();
		config.setId("test-write-1");
		SingleConnection conn = new SingleConnection(null, config, null, Lists.<JdbcFilter> newArrayList(filter));

		ExecutionContextHolder.getContext().add("sql_statement_name", "test");
		Assert.assertEquals("/*id:a2f3e453*/select * from test", filter.sql(conn, "select * from test", true, null));
	}

	@Test(expected = SQLException.class)
	public void test_sql_rejected_by_flow_control() throws SQLException {
		WallFilter filter = new MockWallFilter(11);
		filter.setConfigManagerType("local");
		filter.init();
		
		DataSourceConfig config = new DataSourceConfig();
		config.setId("test-write-1");
		SingleConnection conn = new SingleConnection(null, config, null, Lists.<JdbcFilter> newArrayList(filter));
		ExecutionContextHolder.getContext().add("sql_statement_name", "test");

		filter.sql(conn, "select * from Test", true, null);
	}

	@Test
	public void test_sql_not_reject_by_flow_control() throws SQLException {
		WallFilter filter = new MockWallFilter(9);
		filter.init();
		
		DataSourceConfig config = new DataSourceConfig();
		config.setId("test-write-1");
		SingleConnection conn = new SingleConnection(null, config, null, Lists.<JdbcFilter> newArrayList(filter));

		filter.sql(conn, "select * from Test", true, null);
	}

	@Test
	public void test_load_flow_control_from_config() throws SQLException {
		WallFilter filter = new MockWallFilter(0);
		filter.setConfigManagerType("local");
		filter.init();

		Assert.assertEquals(filter.getFlowControl().size(), 2);
		Assert.assertTrue(filter.getFlowControl().containsKey("ec262bf8"));
		Assert.assertTrue(filter.getFlowControl().containsKey("a2f3e453"));
	}
	
	public class MockWallFilter extends WallFilter{
		private int flowPercent;
		
		public MockWallFilter(int flowPercent){
			this.flowPercent = flowPercent;
		}
		protected int generateFlowPercent() {
			return flowPercent;
		}
	}
}
