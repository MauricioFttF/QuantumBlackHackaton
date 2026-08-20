package com.seuprojeto.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "gemini.api-key=placeholder-key-for-context-load",
        // Without this the context test would run StartupIngestion, which calls Gemini. Tests in
        // this project never touch the network (CLAUDE.md §9.1); with a placeholder key it would
        // only fail and log, but it would still be a real outbound request from a unit run.
        "app.ingestion.on-startup=false",
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
