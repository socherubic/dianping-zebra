/**
 * Project: zebra-client
 * 
 * File Created at 2011-6-28
 * $Id$
 * 
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.zebra.shard.jdbc;

import junit.framework.Assert;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TODO Comment of StatementTest
 * 
 * @author Leo Liang
 * 
 */
public class StatementTest extends ZebraBaseTestCase {

	private Mockery	context	= new Mockery();

	protected String[] getSupportedOps() {
		return new String[] { "getEventNotifier", "setSyncEventNotifier", "getAttachedResultSets",
				"setAttachedResultSets", "setResultSetType", "setResultSetConcurrency", "setResultSetHoldability",
				"getConnectionWrapper", "setConnectionWrapper", "isReadOnly", "setReadOnly", "isAutoCommit",
				"setAutoCommit", "getRouter", "setRouter", "checkClosed", "addBatch", "clearBatch", "close", "execute",
				"executeQuery", "executeUpdate", "getConnection", "getMoreResults", "getResultSet",
				"getResultSetConcurrency", "getResultSetHoldability", "getResultSetType", "getUpdateCount", "isClosed"};
	}

	protected Object getTestObj() {
		return new ShardStatement();
	}

	@Test
	public void testAddBatch() throws Exception {
		ShardStatement stmt = new ShardStatement();
		String[] batchSqls = new String[] { "SELECT * FROM A", "SELECT * FROM B" };
		for (String sql : batchSqls) {
			stmt.addBatch(sql);
		}

		Field field = ShardStatement.class.getDeclaredField("batchedArgs");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<String> batchedArgs = (List<String>) field.get(stmt);
		Assert.assertEquals(2, batchedArgs.size());
		int index = 0;
		for (String sql : batchSqls) {
			Assert.assertEquals(sql, batchedArgs.get(index++));
		}
	}

	@Test
	public void testclearBatch() throws Exception {
		ShardStatement stmt = new ShardStatement();
		String[] batchSqls = new String[] { "SELECT * FROM A", "SELECT * FROM B" };
		for (String sql : batchSqls) {
			stmt.addBatch(sql);
		}

		stmt.clearBatch();

		Field field = ShardStatement.class.getDeclaredField("batchedArgs");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<String> batchedArgs = (List<String>) field.get(stmt);
		Assert.assertEquals(0, batchedArgs.size());
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testClose() throws Exception {
		ShardStatement stmt = new ShardStatement();

		final ResultSet rs1 = context.mock(ResultSet.class, "rs1");
		final ResultSet rs2 = context.mock(ResultSet.class, "rs2");
		Set<ResultSet> attachedRS = new HashSet<ResultSet>();
		attachedRS.add(rs1);
		attachedRS.add(rs2);
		stmt.setAttachedResultSets(attachedRS);

		final Statement stmt1 = context.mock(Statement.class, "stmt1");
		final Statement stmt2 = context.mock(Statement.class, "stmt2");
		List<Statement> actualStmts = new ArrayList<Statement>();
		actualStmts.add(stmt1);
		actualStmts.add(stmt2);
		Field field = ShardStatement.class.getDeclaredField("actualStatements");
		field.setAccessible(true);
		field.set(stmt, actualStmts);

		ShardConnection conn = new ShardConnection();
		stmt.setConnectionWrapper(conn);
		Set<Statement> attachedStatements = new HashSet<Statement>();
		attachedStatements.add(stmt);
		conn.setAttachedStatements(attachedStatements);

		context.checking(new Expectations() {
			{
				try {
					oneOf(stmt1).close();
					oneOf(stmt2).close();
					oneOf(rs1).close();
					oneOf(rs2).close();
				} catch (SQLException e) {
				}
			}
		});

		stmt.close();

		context.assertIsSatisfied();
		Assert.assertEquals(0, stmt.getAttachedResultSets().size());
		Assert.assertEquals(0, ((List<Statement>) field.get(stmt)).size());
		Assert.assertTrue(stmt.isClosed());
	}

	@Test
	public void testCloseThrowException() throws Exception {
		ShardStatement stmt = new ShardStatement();

		final ResultSet rs1 = context.mock(ResultSet.class, "rs1");
		final ResultSet rs2 = context.mock(ResultSet.class, "rs2");
		Set<ResultSet> attachedRS = new HashSet<ResultSet>();
		attachedRS.add(rs1);
		attachedRS.add(rs2);
		stmt.setAttachedResultSets(attachedRS);

		final Statement stmt1 = context.mock(Statement.class, "stmt1");
		final Statement stmt2 = context.mock(Statement.class, "stmt2");
		List<Statement> actualStmts = new ArrayList<Statement>();
		actualStmts.add(stmt1);
		actualStmts.add(stmt2);
		Field field = ShardStatement.class.getDeclaredField("actualStatements");
		field.setAccessible(true);
		field.set(stmt, actualStmts);

		ShardConnection conn = new ShardConnection();
		stmt.setConnectionWrapper(conn);
		Set<Statement> attachedStatements = new HashSet<Statement>();
		attachedStatements.add(stmt);
		conn.setAttachedStatements(attachedStatements);

		context.checking(new Expectations() {
			{
				try {
					oneOf(stmt1).close();
					will(throwException(new SQLException()));
					oneOf(stmt2).close();
					oneOf(rs1).close();
					oneOf(rs2).close();
				} catch (SQLException e) {
				}
			}
		});

		try {
			stmt.close();
			Assert.fail();
		} catch (SQLException e) {
			Assert.assertTrue(true);
		}

		context.assertIsSatisfied();
	}

	@Test
	public void testCloseThrowException2() throws Exception {
		ShardStatement stmt = new ShardStatement();

		final ResultSet rs1 = context.mock(ResultSet.class, "rs1");
		final ResultSet rs2 = context.mock(ResultSet.class, "rs2");
		Set<ResultSet> attachedRS = new HashSet<ResultSet>();
		attachedRS.add(rs1);
		attachedRS.add(rs2);
		stmt.setAttachedResultSets(attachedRS);

		final Statement stmt1 = context.mock(Statement.class, "stmt1");
		final Statement stmt2 = context.mock(Statement.class, "stmt2");
		List<Statement> actualStmts = new ArrayList<Statement>();
		actualStmts.add(stmt1);
		actualStmts.add(stmt2);
		Field field = ShardStatement.class.getDeclaredField("actualStatements");
		field.setAccessible(true);
		field.set(stmt, actualStmts);

		ShardConnection conn = new ShardConnection();
		stmt.setConnectionWrapper(conn);
		Set<Statement> attachedStatements = new HashSet<Statement>();
		attachedStatements.add(stmt);
		conn.setAttachedStatements(attachedStatements);

		context.checking(new Expectations() {
			{
				try {
					oneOf(stmt1).close();
					oneOf(stmt2).close();
					oneOf(rs1).close();
					will(throwException(new SQLException()));
					oneOf(rs2).close();
				} catch (SQLException e) {
				}
			}
		});

		try {
			stmt.close();
			Assert.fail();
		} catch (SQLException e) {
			Assert.assertTrue(true);
		}

		context.assertIsSatisfied();
	}
}