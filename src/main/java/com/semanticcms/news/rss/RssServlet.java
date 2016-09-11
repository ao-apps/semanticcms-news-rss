/*
 * semanticcms-news-rss - RSS feeds for SemanticCMS newsfeeds.
 * Copyright (C) 2016  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of semanticcms-news-rss.
 *
 * semanticcms-news-rss is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * semanticcms-news-rss is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with semanticcms-news-rss.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.semanticcms.news.rss;

import com.aoindustries.encoding.MediaWriter;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder;
import static com.aoindustries.encoding.TextInXhtmlEncoder.encodeTextInXhtml;
import static com.aoindustries.encoding.TextInXhtmlEncoder.textInXhtmlEncoder;
import com.aoindustries.servlet.http.ServletUtil;
import com.semanticcms.core.model.Book;
import com.semanticcms.core.model.Copyright;
import com.semanticcms.core.model.Element;
import com.semanticcms.core.model.NodeBodyWriter;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.servlet.CaptureLevel;
import com.semanticcms.core.servlet.CapturePage;
import com.semanticcms.core.servlet.PageRefResolver;
import com.semanticcms.core.servlet.SemanticCMS;
import com.semanticcms.core.servlet.ServletElementContext;
import com.semanticcms.core.servlet.View;
import com.semanticcms.news.model.News;
import com.semanticcms.news.servlet.NewsUtils;
import com.semanticcms.news.servlet.RssUtils;
import com.semanticcms.news.view.NewsView;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Automated RSS feeds for each page, if it or any of it's
 * child pages have any news.
 * 
 * https://cyber.harvard.edu/rss/rss.html
 * http://webdesign.about.com/od/rss/a/link_rss_feed.htm
 */
@WebServlet("*" + RssUtils.EXTENSION)
public class RssServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final String ENCODING = "UTF-8";

	private static final String RSS_PARAM_PREFIX = "rss.";

	private static final String CHANNEL_PARAM_PREFIX = RSS_PARAM_PREFIX + "channel.";

	private static final String IMAGE_PARAM_PREFIX = CHANNEL_PARAM_PREFIX + "image.";

	/**
	 * TODO: Maven process source to put version into this string.
	 */
	private static final String GENERATOR = RssServlet.class.getName() + " 1.0";

	private static final String DOCS = "https://cyber.harvard.edu/rss/rss.html";

	/**
	 * The default max items to include.
	 */
	private static final int DEFAULT_MAX_ITEMS = 20;

	/**
	 * Gets a book parameter, null if empty.
	 */
	private static String getBookParam(Map<String,String> bookParams, String paramName) {
		String value = bookParams.get(paramName);
		if(value != null && value.isEmpty()) value = null;
		return value;
	}

	private static void writeChannelParamElement(Map<String,String> bookParams, String elementName, PrintWriter out) throws IOException {
		String value = getBookParam(bookParams, CHANNEL_PARAM_PREFIX + elementName);
		if(value != null) {
			out.print("        <");
			out.print(elementName);
			out.print('>');
			encodeTextInXhtml(value, out);
			out.print("</");
			out.print(elementName);
			out.println('>');
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// Path extra info not allowed
		if(req.getPathInfo() != null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		// Query string not allowed
		if(req.getQueryString() != null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String basePath;
		{
			String servletPath = req.getServletPath();
			// Must end in expected extension
			if(!servletPath.endsWith(RssUtils.EXTENSION)) {
				resp.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			basePath = servletPath.substring(0, servletPath.length() - RssUtils.EXTENSION.length());
		}
		// Try to find the page, jspx, then jsp, then direct URL without extension
		ServletContext servletContext = getServletContext();
		String pagePath = null;
		for (String extension : RssUtils.getResourceExtensions()) {
			pagePath = basePath + extension;
			try {
				if(
					!RssUtils.isProtectedExtension(pagePath)
					&& servletContext.getResource(pagePath) != null
				) {
					break;
				}
			} catch(MalformedURLException e) {
				// Assume does not exist
			}
		}
		assert pagePath != null : "The last extension should be the default if none matched";
		if(RssUtils.isProtectedExtension(pagePath)) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		// Used several places below
		SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
		// Find book and path
		Book book = semanticCMS.getBook(pagePath);
		PageRef pageRef;
		{
			if(book == null) {
				resp.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			pageRef = new PageRef(
				book,
				pagePath.substring(book.getPathPrefix().length())
			);
		}
		// Try to capture the page
		Page page = CapturePage.capturePage(
			servletContext,
			req,
			resp,
			pageRef,
			CaptureLevel.META
		);
		// Find the news view, which this RSS extends and iteroperates with
		View view;
		{
			view = semanticCMS.getViewsByName().get(NewsView.VIEW_NAME);
			if(view == null) throw new ServletException("View not found: " + NewsView.VIEW_NAME);
		}
		resp.reset();
		resp.setContentType(RssUtils.CONTENT_TYPE);
		resp.setCharacterEncoding(ENCODING);
		PrintWriter out = resp.getWriter();
		out.println("<?xml version=\"1.0\" encoding=\"" + ENCODING + "\"?>");
		out.println("<rss version=\"2.0\">");
		out.println("    <channel>");
		String channelTitle = view.getTitle(servletContext, req, resp, page);
		out.print("        <title>");
		encodeTextInXhtml(channelTitle, out);
		out.println("</title>");
		String channelLink;
		{
			String servletPath = pageRef.getServletPath();
			if(!view.isDefault()) {
				servletPath += "?view=" + URLEncoder.encode(view.getName(), ENCODING);
			}
			channelLink = ServletUtil.getAbsoluteURL(
				req,
				resp.encodeURL(servletPath)
			);
		}
		out.print("        <link>");
		encodeTextInXhtml(channelLink, out);
		out.println("</link>");
		out.print("        <description>");
		encodeTextInXhtml(view.getDescription(page), out);
		out.println("</description>");
		Copyright copyright = view.getCopyright(servletContext, req, resp, page);
		if(copyright != null && !copyright.isEmpty()) {
			out.print("        <copyright>");
			encodeTextInXhtml(copyright.toString(), out);
			out.println("</copyright>");
		}
		Map<String,String> bookParams = book.getParam();
		writeChannelParamElement(bookParams, "managingEditor", out);
		writeChannelParamElement(bookParams, "webMaster", out);
		out.print("        <generator>");
		encodeTextInXhtml(GENERATOR, out);
		out.println("</generator>");
		out.print("        <docs>");
		encodeTextInXhtml(DOCS, out);
		out.println("</docs>");
		writeChannelParamElement(bookParams, "ttl", out);
		// image
		{
			String imageUrl = getBookParam(bookParams, IMAGE_PARAM_PREFIX + "url");
			String imageWidth = getBookParam(bookParams, IMAGE_PARAM_PREFIX + "width");
			String imageHeight = getBookParam(bookParams, IMAGE_PARAM_PREFIX + "height");
			String imageDescription = getBookParam(bookParams, IMAGE_PARAM_PREFIX + "description");
			if(imageUrl != null) {
				out.println("        <image>");
				out.print("            <url>");
				ServletUtil.getAbsoluteURL(
					req,
					resp.encodeURL(book.getPathPrefix() + imageUrl),
					textInXhtmlEncoder,
					out
				);
				out.println("</url>");
				out.print("            <title>");
				encodeTextInXhtml(channelTitle, out);
				out.println("</title>");
				out.print("            <link>");
				encodeTextInXhtml(channelLink, out);
				out.println("</link>");
				if(imageWidth != null) {
					out.print("            <width>");
					encodeTextInXhtml(imageWidth, out);
					out.println("</width>");
				}
				if(imageHeight != null) {
					out.print("            <height>");
					encodeTextInXhtml(imageHeight, out);
					out.println("</height>");
				}
				if(imageDescription != null) {
					out.print("            <description>");
					encodeTextInXhtml(imageDescription, out);
					out.println("</description>");
				}
				out.println("        </image>");
			} else {
				// Others must not be provided
				if(imageWidth != null) throw new ServletException("RSS image width without url");
				if(imageHeight != null) throw new ServletException("RSS image height without url");
				if(imageDescription != null) throw new ServletException("RSS image description without url");
			}
		}
		writeChannelParamElement(bookParams, "rating", out);
		// textInput not supported
		// skipHours not supported
		// skipDays not supported
		int maxItems;
		{
			String maxItemsVal = getBookParam(bookParams, CHANNEL_PARAM_PREFIX + "maxItems");
			if(maxItemsVal != null) {
				maxItems = Integer.parseInt(maxItemsVal);
				if(maxItems < 1) throw new ServletException("RSS maxItems may not be less than one: " + maxItems);
			} else {
				maxItems = DEFAULT_MAX_ITEMS;
			}
		}
		List<News> allNews = NewsUtils.findAllNews(servletContext, req, resp, page);
		int numItems = allNews.size();
		if(numItems > maxItems) numItems = maxItems;
		for(int i=0; i<numItems; i++) {
			News news = allNews.get(i);
			out.println("        <item>");
			out.print("            <title>");
			encodeTextInXhtml(news.getTitle(), out);
			out.println("</title>");
			out.print("            <link>");
			PageRef targetPageRef = PageRefResolver.getPageRef(
				servletContext,
				req,
				news.getBook(),
				news.getTargetPage()
			);
			StringBuilder targetServletPath = new StringBuilder(targetPageRef.getServletPath());
			if(!news.getView().equals(SemanticCMS.DEFAULT_VIEW_NAME)) {
				targetServletPath.append("?view=").append(URLEncoder.encode(news.getView(), ENCODING));
			}
			if(news.getElement() != null) {
				targetServletPath.append('#').append(news.getElement());
			}
			ServletUtil.getAbsoluteURL(
				req,
				resp.encodeURL(targetServletPath.toString()),
				textInXhtmlEncoder,
				out
			);
			out.println("</link>");
			// TODO: Prefer body over description?
			String description = news.getDescription();
			if(description != null) {
				// Since description in RSS 2.0 allows HTML, and this is a text-only description, this has to be doubly encoded
				StringBuilder encoded = new StringBuilder(description.length() * 5/4); // Allow roughly 25% increase before growing stringbuilder
				encodeTextInXhtml(description, encoded);
				out.print("            <description>");
				encodeTextInXhtml(encoded, out);
				out.println("</description>");
			} else {
				// Capture news now in "body" mode, since findAllNews only did meta for fast search
				// TODO: body: Is there a way to capture news at "body" level while other parts at "meta" level?
				//       This recapturing is clunky and full body capture of all would be inefficient.
				Element recaptured = CapturePage.capturePage(servletContext, req, resp, pageRef, CaptureLevel.BODY).getElementsById().get(news.getId());
				if(!(recaptured instanceof News)) throw new ServletException("recaptured is not news: " + recaptured.getClass().getName());
				if(recaptured.getBody().getLength() > 0) {
					// TODO: Automatic absolute links on body content of news tags, resetting on capturing other pages, or do we just trust RSS to correctly do relative links?
					out.print("            <description>");
					MediaWriter encoder = new MediaWriter(textInXhtmlEncoder, out);
					recaptured.getBody().writeTo(
						new NodeBodyWriter(
							recaptured,
							encoder,
							new ServletElementContext(servletContext, req, resp)
						)
					);
					out.println("</description>");
				}
			}
			// author possible here, but Author does not currently have email address
			out.print("            <guid>");
			Page newsPage = news.getPage();
			String guidServletPath =
				newsPage.getPageRef().getServletPath()
				+ '#'
				+ news.getId()
			;
			ServletUtil.getAbsoluteURL(
				req,
				resp.encodeURL(guidServletPath),
				textInXhtmlEncoder,
				out
			);
			out.println("</guid>");
			out.print("            <pubDate>");
			// http://stackoverflow.com/questions/15247742/rfc-822-date-time-format-in-rss-2-0-feeds-cet-not-accepted
			encodeTextInXhtml(new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(news.getPubDate().toDate()), out);
			out.println("</pubDate>");
			// source if from a different page
			if(!page.equals(newsPage)) {
				out.print("            <source url=\"");
				ServletUtil.getAbsoluteURL(
					req,
					resp.encodeURL(RssUtils.getRssServletPath(newsPage)),
					textInXhtmlAttributeEncoder,
					out
				);
				out.print("\">");
				encodeTextInXhtml(view.getTitle(servletContext, req, resp, newsPage), out);
				out.println("</source>");
			}
			out.println("        </item>");
		}
		out.println("    </channel>");
		out.println("</rss>");
	}
}
