package com.riskwarning.common.reliability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 可靠模式由使用方服务显式启用，公共配置不得把数据库依赖扩散到其他服务。 */
class ReliabilityServiceOptInConfigurationTest {

    @Test
    void commonConfigurationDoesNotEnableReliabilityGlobally() throws IOException {
        assertThat(property("build/nacos/common-dev.yaml", "assessment.reliability.enabled"))
                .isNull();
    }

    @Test
    void durableWorkServicesOptInByDefault() throws IOException {
        assertThat(property("risk-warning-org/src/main/resources/application.yml",
                "assessment.reliability.enabled"))
                .isEqualTo("${ASSESSMENT_RELIABILITY_ENABLED:true}");
        assertThat(property("risk-warning-processing/src/main/resources/application.yml",
                "assessment.reliability.enabled"))
                .isEqualTo("${ASSESSMENT_RELIABILITY_ENABLED:true}");
        assertThat(property("risk-warning-report/src/main/resources/application.yml",
                "assessment.reliability.enabled"))
                .isEqualTo("${ASSESSMENT_RELIABILITY_ENABLED:true}");
    }

    @Test
    void unrelatedServicesKeepReliabilityDisabled() throws IOException {
        assertThat(property("risk-warning-knowledge/src/main/resources/application.yml",
                "assessment.reliability.enabled"))
                .isEqualTo(Boolean.FALSE);
        assertThat(property("risk-warning-notification/src/main/resources/application.yml",
                "assessment.reliability.enabled"))
                .isEqualTo(Boolean.FALSE);
    }

    private Object property(String relativePath, String key) throws IOException {
        Path file = resolveProjectFile(relativePath);
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(file.getFileName().toString(), new FileSystemResource(file));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Path resolveProjectFile(String relativePath) {
        Path fromRoot = Paths.get(relativePath);
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        Path fromModule = Paths.get("..", relativePath);
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("找不到配置文件: " + relativePath);
    }
}
