<%@ page contentType="text/html; charset=utf-8"%>
<%@ taglib prefix="a" uri="/WEB-INF/app.tld"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>

<jsp:useBean id="ctx"
	type="com.dianping.zebra.admin.admin.page.index.Context"
	scope="request" />
<jsp:useBean id="payload"
	type="com.dianping.zebra.admin.admin.page.index.Payload"
	scope="request" />
<jsp:useBean id="model"
	type="com.dianping.zebra.admin.admin.page.index.Model" scope="request" />

<a:layout>
	<div>
		<table class="table table-bordered table-striped table-condensed">
			<thead>
				<tr>
					<th>Database</th>
					<th>Auto-Replace Num</th>
					<th>Upgrade-Dal Num</th>
					<th>Totoal Num</th>
				</tr>
			</thead>
			<tbody>
				<c:forEach var="database" items="${model.report.databases}">
					<tr id="database-info-${database.key}">
						<td><a href="?op=database&database=${database.key}">${database.key}</a></td>
						<td>${database.value.replacedDataSource}</td>
						<td>${database.value.groupDataSource }</td>
						<td>${database.value.totalDataSource }</td>
					</tr>
				</c:forEach>
			</tbody>
		</table>
	</div>
</a:layout>