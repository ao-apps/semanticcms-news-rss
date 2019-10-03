/*
 * semanticcms-news-rss - RSS feeds for SemanticCMS newsfeeds.
 * Copyright (C) 2016, 2017, 2019  AO Industries, Inc.
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
import com.aoindustries.net.URIEncoder;
import com.aoindustries.servlet.ServletContextCache;
import com.aoindustries.servlet.http.HttpServletUtil;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
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
 * <ul>
 *   <li><a href="https://cyber.harvard.edu/rss/rss.html">https://cyber.harvard.edu/rss/rss.html</a></li>
 *   <li><a href="http://webdesign.about.com/od/rss/a/link_rss_feed.htm">http://webdesign.about.com/od/rss/a/link_rss_feed.htm</a></li>
 * </ul>
 * 
 * TODO: Generate or convert all relative paths to absolute paths to be in strict compliance with RSS.
 *       Then test on Android gReader app which does not currently handle relative paths.
 */
@WebServlet("*" + RssUtils.EXTENSION)
public class RssServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Charset ENCODING = StandardCharsets.UTF_8;

	private static final String RSS_PARAM_PREFIX = "rss.";

	private static final String CHANNEL_PARAM_PREFIX = RSS_PARAM_PREFIX + "channel.";

	private static final String IMAGE_PARAM_PREFIX = CHANNEL_PARAM_PREFIX + "image.";

	private static final String DOCS = "https://cyber.harvard.edu/rss/rss.html";

	/**
	 * See <a href="http://stackoverflow.com/questions/15247742/rfc-822-date-time-format-in-rss-2-0-feeds-cet-not-accepted">
	 * http://stackoverflow.com/questions/15247742/rfc-822-date-time-format-in-rss-2-0-feeds-cet-not-accepted
	 * </a>
	 */
	private static final String RFC_822_FORMAT = "EEE, dd MMM yyyy HH:mm:ss Z";

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

	/**
	 * The response is not given to getLastModified, but we need it for captures to get
	 * the last modified.
	 */
	private static final String RESPONSE_IN_REQUEST_ATTRIBUTE = RssServlet.class.getName() + ".responseInRequest";

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		Object old = req.getAttribute(RESPONSE_IN_REQUEST_ATTRIBUTE);
		try {
			req.setAttribute(RESPONSE_IN_REQUEST_ATTRIBUTE, resp);
			super.service(req, resp);
		} finally {
			req.setAttribute(RESPONSE_IN_REQUEST_ATTRIBUTE, old);
		}
	}

	/**
	 * Finds the page, returns {@code null} when not able to find the page.
	 */
	private static Page findPage(
		ServletContext servletContext,
		HttpServletRequest req,
		HttpServletResponse resp,
		SemanticCMS semanticCMS
	) throws ServletException, IOException {
		// Path extra info not allowed
		if(req.getPathInfo() != null) {
			return null;
		}
		// Query string not allowed
		if(req.getQueryString() != null) {
			return null;
		}
		String basePath;
		{
			String servletPath = req.getServletPath();
			// Must end in expected extension
			if(!servletPath.endsWith(RssUtils.EXTENSION)) {
				return null;
			}
			basePath = servletPath.substring(0, servletPath.length() - RssUtils.EXTENSION.length());
		}
		// Try to find the page, jspx, then jsp, then direct URL without extension
		String pagePath = null;
		for (String extension : RssUtils.getResourceExtensions()) {
			pagePath = basePath + extension;
			try {
				if(
					!RssUtils.isProtectedExtension(pagePath)
					&& ServletContextCache.getResource(servletContext, pagePath) != null
				) {
					break;
				}
			} catch(MalformedURLException e) {
				// Assume does not exist
			}
		}
		assert pagePath != null : "The last extension should be the default if none matched";
		if(RssUtils.isProtectedExtension(pagePath)) {
			return null;
		}
		// Find book and path
		Book book = semanticCMS.getBook(pagePath);
		PageRef pageRef;
		{
			if(book == null) {
				return null;
			}
			pageRef = new PageRef(
				book,
				pagePath.substring(book.getPathPrefix().length())
			);
		}
		// Capture the page
		return CapturePage.capturePage(
			servletContext,
			req,
			resp,
			pageRef,
			CaptureLevel.META
		);
	}

	/**
	 * Finds the news view.
	 *
	 * @throws ServletException when cannot find the news view
	 */
	private static View findNewsView(SemanticCMS semanticCMS) throws ServletException {
		// Find the news view, which this RSS extends and iteroperates with
		View view = semanticCMS.getViewsByName().get(NewsView.VIEW_NAME);
		if(view == null) throw new ServletException("View not found: " + NewsView.VIEW_NAME);
		return view;
	}

	/**
	 * Finds the news, returns {@code null} when not able to find the news.
	 * Limits the number of news entries per book "maxItems" settings.
	 */
	private static List<News> findNews(
		ServletContext servletContext,
		HttpServletRequest req,
		HttpServletResponse resp,
		Page page
	) throws ServletException, IOException {
		Book book = page.getPageRef().getBook();
		Map<String,String> bookParams = book.getParam();
		// Find the news
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
		if(allNews.size() > maxItems) allNews = allNews.subList(0, maxItems);
		return allNews;
	}

	@Override
	protected long getLastModified(HttpServletRequest req) {
		try {
			HttpServletResponse resp = (HttpServletResponse)req.getAttribute(RESPONSE_IN_REQUEST_ATTRIBUTE);
			ServletContext servletContext = getServletContext();
			SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
			// Used several places below
			Page page = findPage(servletContext, req, resp, semanticCMS);
			if(page == null) {
				return -1;
			}
			List<News> rssNews = findNews(
				servletContext,
				req,
				resp,
				page
			);
			if(rssNews == null || rssNews.isEmpty()) {
				return -1;
			}
			return rssNews.get(0).getPubDate().getMillis();
		} catch(ServletException | IOException e) {
			log("getLastModified failed", e);
			return -1;
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		ServletContext servletContext = getServletContext();
		SemanticCMS semanticCMS = SemanticCMS.getInstance(servletContext);
		// Used several places below
		Page page = findPage(servletContext, req, resp, semanticCMS);
		if(page == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		PageRef pageRef = page.getPageRef();
		Book book = page.getPageRef().getBook();
		Map<String,String> bookParams = book.getParam();
		View view = findNewsView(semanticCMS);
		List<News> rssNews = findNews(
			servletContext,
			req,
			resp,
			page
		);
		if(rssNews == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		resp.resetBuffer();
		resp.setContentType(RssUtils.CONTENT_TYPE);
		resp.setCharacterEncoding(ENCODING.name());
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
				servletPath += "?view=" + URIEncoder.encodeURIComponent(view.getName());
			}
			channelLink = URIEncoder.encodeURI( // Encode again to force RFC 3986 US-ASCII
				resp.encodeURL(
					HttpServletUtil.getAbsoluteURL(
						req,
						URIEncoder.encodeURI(
							servletPath
						)
					)
				)
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
		writeChannelParamElement(bookParams, "managingEditor", out);
		writeChannelParamElement(bookParams, "webMaster", out);
		DateFormat rfc822 = new SimpleDateFormat(RFC_822_FORMAT);
		// lastBuildDate is the most recent of the news items listed, which will have been sorted to the top of the news
		if(!rssNews.isEmpty()) {
			out.print("        <lastBuildDate>");
			encodeTextInXhtml(rfc822.format(rssNews.get(0).getPubDate().toDate()), out);
			out.println("</lastBuildDate>");
		}
		out.print("        <generator>");
		encodeTextInXhtml(RssServlet.class.getName(), out);
		out.print(' ');
		encodeTextInXhtml(Maven.properties.getProperty("project.version"), out);
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
				URIEncoder.encodeURI( // Encode again to force RFC 3986 US-ASCII
					resp.encodeURL(
						HttpServletUtil.getAbsoluteURL(
							req,
							URIEncoder.encodeURI(
								book.getPathPrefix() + imageUrl
							)
						)
					),
					out,
					textInXhtmlEncoder
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
		for(News news : rssNews) {
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
				targetServletPath.append("?view=");
				URIEncoder.encodeURIComponent(news.getView(), targetServletPath);
			}
			if(news.getElement() != null) {
				targetServletPath.append('#');
				URIEncoder.encodeURIComponent(news.getElement(), targetServletPath);
			}
			URIEncoder.encodeURI( // Encode again to force RFC 3986 US-ASCII
				resp.encodeURL(
					HttpServletUtil.getAbsoluteURL(
						req,
						URIEncoder.encodeURI(
							targetServletPath.toString()
						)
					)
				),
				out,
				textInXhtmlEncoder
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
				// TODO: Concurrency: Is limited concurrent capture possible here?
				//       If concurrency at 16, for example, could we reasonably dispatch concurrent capturePageInBook
				//       while serializing to write on the main thread?
				String newsId = news.getId();
				PageRef newsPageRef = news.getPage().getPageRef();
				Element recaptured = CapturePage.capturePage(servletContext, req, resp, newsPageRef, CaptureLevel.BODY).getElementsById().get(newsId);
				if(recaptured == null) throw new ServletException("recaptured failed: pageRef = " + newsPageRef + ", newsId = " + newsId);
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
				+ URIEncoder.encodeURIComponent(news.getId())
			;
			URIEncoder.encodeURI( // Encode again to force RFC 3986 US-ASCII
				resp.encodeURL(
					HttpServletUtil.getAbsoluteURL(
						req,
						URIEncoder.encodeURI(
							guidServletPath
						)
					)
				),
				out,
				textInXhtmlEncoder
			);
			out.println("</guid>");
			out.print("            <pubDate>");
			encodeTextInXhtml(rfc822.format(news.getPubDate().toDate()), out);
			out.println("</pubDate>");
			// source if from a different page
			if(!page.equals(newsPage)) {
				out.print("            <source url=\"");
				URIEncoder.encodeURI( // Encode again to force RFC 3986 US-ASCII
					resp.encodeURL(
						HttpServletUtil.getAbsoluteURL(
							req,
							URIEncoder.encodeURI(
								RssUtils.getRssServletPath(newsPage)
							)
						)
					),
					out,
					textInXhtmlAttributeEncoder
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
