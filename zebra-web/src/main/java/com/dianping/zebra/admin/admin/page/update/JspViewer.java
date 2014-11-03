package com.dianping.zebra.admin.admin.page.update;

import com.dianping.zebra.admin.admin.AdminPage;
import org.unidal.web.mvc.view.BaseJspViewer;

public class JspViewer extends BaseJspViewer<AdminPage, Action, Context, Model> {
	@Override
	protected String getJspFilePath(Context ctx, Model model) {
		Action action = model.getAction();

		switch (action) {
		case VIEW:
			return JspFile.VIEW.getPath();
		case APP:
			break;
		case DATABASE:
			break;
		default:
			break;
		}

		throw new RuntimeException("Unknown action: " + action);
	}
}