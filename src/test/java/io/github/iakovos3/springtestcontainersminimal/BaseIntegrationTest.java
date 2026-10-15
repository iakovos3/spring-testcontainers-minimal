package io.github.iakovos3.springtestcontainersminimal;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(IntegrationTestContainers.class)
public abstract class BaseIntegrationTest {
}