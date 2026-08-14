package io.github.brick.data;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DependencyRuleTest {

    // 从编译输出目录直接导入生产类字节码。
    // ArchUnit importPackages/importClasspath 依赖 classloader 解析 classpath，
    // 在 surefire forked JVM 下不可靠（返回空）；importPath 走文件系统更稳。
    // 需 ArchUnit >= 1.4.1（ASM 9.8 支持 Java 25 字节码 major version 69）。
    private final JavaClasses classes = new ClassFileImporter()
            .importPath(Paths.get("target/classes"));

    @Test
    void dataDoesNotDependOnBusinessOrContractOrDbserver() {
        noClasses().that().resideInAPackage("io.github.brick.data..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.github.brick.web..",
                        "io.github.brick.contract..",
                        "io.github.brick.dbserver..")
                .check(classes);
    }
}
