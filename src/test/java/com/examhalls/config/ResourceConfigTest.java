package com.examhalls.config;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Guards against configuration mistakes that fail silently at runtime. */
class ResourceConfigTest {

    /** Logback falls back to defaults without failing the app when its XML is invalid. */
    @Test
    void logbackXmlIsWellFormed() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback.xml")) {
            assertNotNull(in);
            SAXParserFactory.newInstance().newSAXParser().parse(in, new org.xml.sax.helpers.DefaultHandler());
        }
    }

    @Test
    void bundledDefaultsCarryNoPasswordAndFailFast() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        assertEquals("", p.getProperty("db.password"), "never ship credentials inside the jar");
        assertTrue(Long.parseLong(p.getProperty("db.pool.initializationFailTimeoutMs")) > 0,
                "background retries with a wrong password can lock the Oracle account");
    }
}
