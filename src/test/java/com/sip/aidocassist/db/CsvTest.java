package com.sip.aidocassist.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CsvTest {

    @Test
    void readsSkbSyncSourcesRow() {
        // Verbatim from SKB skb/init-data/sync_sources.csv: quoted JSON with "" escapes and backslashes.
        String text = "\uFEFFsource,name,config,default_group_ids,enabled\r\n"
                + "filesystem,SKB samples,\"{\"\"folder_path\"\": \"\"C:\\\\Users\\\\Aung Myat Hein\\\\Projects\\\\SIP\\\\SKB\\\\samples\"\"}\",general,true\r\n";
        var rows = Csv.toMaps(Csv.parse(text.substring(1)));
        assertEquals(1, rows.size());
        assertEquals("{\"folder_path\": \"C:\\\\Users\\\\Aung Myat Hein\\\\Projects\\\\SIP\\\\SKB\\\\samples\"}", rows.get(0).get("config"));
        assertEquals("true", rows.get(0).get("enabled"));
    }

    @Test
    void quotedNewlinesHeaderOnlyAndBadRows() {
        assertEquals(List.of(List.of("a", "x\ny"), List.of("1", "")), Csv.parse("a,\"x\ny\"\n1,"));
        assertEquals(List.of(), Csv.toMaps(Csv.parse("username,group_name\n"))); // SKB's empty user_groups.csv
        assertEquals(List.of(Map.of("a", "1")), Csv.toMaps(Csv.parse("a\n1\n\n")));
        assertThrows(IllegalArgumentException.class, () -> Csv.toMaps(Csv.parse("a,b\n1\n")));
    }
}
