/**
 * Project: ${zebra-client.aid}
 * 
 * File Created at 2011-6-7
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
package com.dianping.zebra.shard.router;

import java.util.List;

import com.dianping.zebra.shard.merge.MergeContext;

/**
 * 
 * @author hao.zhu
 *
 */
public class RouterResult {

	private List<RouterTarget> sqls;

	private List<Object> params;

	private MergeContext mergeContext;

	public List<RouterTarget> getSqls() {
		return sqls;
	}

	public void setSqls(List<RouterTarget> sqls) {
		this.sqls = sqls;
	}

	public List<Object> getParams() {
		return params;
	}

	public void setParams(List<Object> newParams) {
		this.params = newParams;
	}

	public MergeContext getMergeContext() {
		return mergeContext;
	}

	public void setMergeContext(MergeContext mergeContext) {
		this.mergeContext = mergeContext;
	}
}
