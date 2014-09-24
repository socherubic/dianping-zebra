package com.dianping.zebra.group.filter.wall;

/**
 * Created by Dozer on 9/24/14.
 */

import junit.framework.Assert;
import org.junit.Test;

public class WallFilterTest {
	@Test
	public void test_add_id_to_sql() {
		WallFilter filter = new WallFilter();
		filter.addIdToSql("select * from user");
	}

	@Test
	public void test_get_id_from_sql() {
		WallFilter filter = new WallFilter();
		Assert.assertEquals("7yhgtr45ty", filter.getIdFromSQL("select * from user/*7yhgtr45ty*/"));
		Assert.assertEquals(null, filter.getIdFromSQL("select * from user/*7yhgtr45ty111*/"));
	}
}
