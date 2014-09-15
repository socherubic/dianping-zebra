package com.dianping.zebra.group.filter.stat;

import com.dianping.zebra.group.filter.AbstractJdbcFilter;
import com.dianping.zebra.group.filter.JdbcMetaData;
import com.foundationdb.sql.StandardException;
import com.foundationdb.sql.parser.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by Dozer on 9/2/14.
 */
public class StatFilter extends AbstractJdbcFilter {

	@Override public void closeGroupConnectionError(JdbcMetaData metaData, Exception exp) {
		StatContext.getDataSourceSummary().getCloseGroupConnectionErrorCount().incrementAndGet();
		StatContext.getDataSource(metaData).getCloseGroupConnectionErrorCount().incrementAndGet();
	}

	@Override public void closeGroupConnectionSuccess(JdbcMetaData metaData) {
		StatContext.getDataSourceSummary().getCloseGroupConnectionSuccessCount().incrementAndGet();
		StatContext.getDataSource(metaData).getCloseGroupConnectionSuccessCount().incrementAndGet();
	}

	@Override public void executeError(JdbcMetaData metaData, Exception exp) {
		visitNode(metaData, exp);
	}

	@Override public void executeSuccess(JdbcMetaData metaData) {
		visitNode(metaData);
	}

	@Override public void getGroupConnectionError(JdbcMetaData metaData, Exception exp) {
		StatContext.getDataSourceSummary().getGetGroupConnectionErrorCount().incrementAndGet();
		StatContext.getDataSource(metaData).getGetGroupConnectionErrorCount().incrementAndGet();
	}

	@Override public void getGroupConnectionSuccess(JdbcMetaData metaData) {
		StatContext.getDataSourceSummary().getGetGroupConnectionSuccessCount().incrementAndGet();
		StatContext.getDataSource(metaData).getGetGroupConnectionSuccessCount().incrementAndGet();
	}

	private void visitNode(JdbcMetaData metaData, Exception exp) {
		try {
			new SqlStatVisitor(metaData, exp).visit(metaData.getNode());
		} catch (StandardException e) {
		}

		if (metaData.getBatchedNode() == null) {
			return;
		}
		for (StatementNode node : metaData.getBatchedNode()) {
			try {
				new SqlStatVisitor(metaData, exp).visit(node);
			} catch (StandardException e) {
			}
		}

	}

	private void visitNode(JdbcMetaData node) {
		visitNode(node, null);
	}

	class SqlStatVisitor implements Visitor {
		private final Exception exception;

		private final JdbcMetaData metaData;

		public SqlStatVisitor(JdbcMetaData metaData) {
			this.exception = null;
			this.metaData = metaData;
		}

		public SqlStatVisitor(JdbcMetaData metadata, Exception exp) {
			this.metaData = metadata;
			this.exception = exp;
		}

		public void cursorNode(CursorNode node) throws StandardException {
			visit(node.getResultSetNode());
		}

		public void deleteNode(DeleteNode node) {
			increment(StatContext.getExecuteSummary().getDeleteSuccessCount(),
					StatContext.getExecuteSummary().getDeleteErrorCount());
			increment(StatContext.getExecute(metaData).getDeleteSuccessCount(),
					StatContext.getExecute(metaData).getDeleteErrorCount());
		}

		private void increment(AtomicLong success, AtomicLong error) {
			if (exception == null) {
				success.incrementAndGet();
			} else {
				error.incrementAndGet();
			}
		}

		public void insertNode(InsertNode node) {
			increment(StatContext.getExecuteSummary().getInsertSuccessCount(),
					StatContext.getExecuteSummary().getInsertErrorCount());
			increment(StatContext.getExecute(metaData).getInsertSuccessCount(),
					StatContext.getExecute(metaData).getInsertErrorCount());
		}

		public void selectNode(SelectNode node) {
			increment(StatContext.getExecuteSummary().getSelectSuccessCount(),
					StatContext.getExecuteSummary().getSelectErrorCount());
			increment(StatContext.getExecute(metaData).getSelectSuccessCount(),
					StatContext.getExecute(metaData).getSelectErrorCount());
		}

		@Override public boolean skipChildren(Visitable node) throws StandardException {
			return false;
		}

		@Override public boolean stopTraversal() {
			return false;
		}

		public void updateNode(UpdateNode node) {
			increment(StatContext.getExecuteSummary().getUpdateSuccessCount(),
					StatContext.getExecuteSummary().getUpdateErrorCount());
			increment(StatContext.getExecute(metaData).getUpdateSuccessCount(),
					StatContext.getExecute(metaData).getUpdateErrorCount());
		}

		@Override public Visitable visit(Visitable node) throws StandardException {
			if (node == null) {
				return node;
			}

			switch (((QueryTreeNode) node).getNodeType()) {
			case NodeTypes.CURSOR_NODE: {
				cursorNode((CursorNode) node);
				break;
			}
			case NodeTypes.SELECT_NODE: {
				selectNode((SelectNode) node);
				break;
			}
			case NodeTypes.UPDATE_NODE: {
				updateNode((UpdateNode) node);
				break;
			}
			case NodeTypes.INSERT_NODE: {
				insertNode((InsertNode) node);
				break;
			}
			case NodeTypes.DELETE_NODE: {
				deleteNode((DeleteNode) node);
				break;
			}
			}
			return node;
		}

		@Override public boolean visitChildrenFirst(Visitable node) {
			return false;
		}
	}
}
