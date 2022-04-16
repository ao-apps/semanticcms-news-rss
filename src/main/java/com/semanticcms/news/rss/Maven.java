/*
 * semanticcms-news-rss - RSS feeds for SemanticCMS newsfeeds.
 * Copyright (C) 2017, 2021, 2022  AO Industries, Inc.
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
 * along with semanticcms-news-rss.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.semanticcms.news.rss;

import com.aoapps.lang.util.PropertiesUtils;
import java.io.IOException;
import java.util.Properties;

/**
 * @author  AO Industries, Inc.
 */
final class Maven {

	/** Make no instances. */
	private Maven() {throw new AssertionError();}

	static final Properties properties;
	static {
		try {
			properties = PropertiesUtils.loadFromResource(Maven.class, "Maven.properties");
		} catch(IOException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
