package cn.yzapp.androidcontainer.core.engine.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板准入守门人：模板描述文件与正文的可解析性、元数据一致性与解析器容错行为，
 * 全部在这里自动化校验，避免「模板随版本悄悄失效」。
 *
 * 之所以把校验放在**测试**而不是构建脚本：模板内容是数据（YAML），解析器只有一份（`TemplateCatalog`），
 * 测试跑的就是 App 运行时用的那套逻辑，天然不会漂移。挂在本模块测试套件上，`./gradlew test` 必跑。
 *
 * 本仓库的 `src/main/templates/` 目录**有意留空**（内置模板目录为空时构建仍可用）：
 * 与产品内置目录绑定的强断言（模板清单、免费边界、分组数、四语言完整性等）不在这里。
 * 模板文件的编写规范见 `docs/template_authoring_guide.md`，
 * 字段齐全的示例见 `core/engine/src/test/resources/examples/hello-world.yaml`。
 */
class TemplateCatalogTest {

    private val catalog = TemplateCatalog.builtIn()
    private val templates = catalog.templates

    @Test
    fun `the built-in catalog parses without a single issue`() {
        // 这一条覆盖了：未知键、分类/提示引用不存在、id 与文件名不一致、id 重复、
        // 文案缺失 en、webPorts 不在正文端口内、PID 1 风险未告知、port_direct 与 webPorts 不成对。
        // 内置目录为空时同样必须零 issue（空分类学按 EMPTY 处理，不算问题）。
        assertEquals(
            "built-in templates must be clean; otherwise the very same problems would show up at runtime",
            emptyList<TemplateIssue>(),
            catalog.issues,
        )
    }

    @Test
    fun `every template parses into services and uses only supported compose keys`() {
        templates.forEach { template ->
            assertTrue("template ${template.id} has no services", template.services.isNotEmpty())
            val unsupported = template.issues.filter { it.kind == ComposeIssueKind.UNSUPPORTED_KEY }
            assertTrue(
                "template ${template.id} uses unsupported keys: " +
                    unsupported.joinToString { "${it.service}/${it.key}" },
                unsupported.isEmpty(),
            )
        }
    }

    @Test
    fun `no template has a dependency cycle`() {
        templates.forEach { template ->
            assertTrue(
                "template ${template.id} has a dependency cycle: ${template.spec.cyclicServices()}",
                template.spec.cyclicServices().isEmpty(),
            )
        }
    }

    @Test
    fun `no two services in a template share a container port`() {
        templates.forEach { template ->
            // proot 与手机共享网络栈，同一项目内两服务占用同一端口必然冲突
            val conflicts = template.services
                .flatMap { service -> service.ports.map { containerPort(it) to service.name } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.distinct().size > 1 }
            assertTrue("template ${template.id} has conflicting container ports: $conflicts", conflicts.isEmpty())
        }
    }

    @Test
    fun `every declared port is numeric and in range`() {
        templates.forEach { template ->
            template.services.forEach { service ->
                service.ports.forEach { entry ->
                    val port = containerPort(entry).toIntOrNull()
                    assertNotNull("template ${template.id} has a non-numeric port \"$entry\"", port)
                    assertTrue("template ${template.id} port $port is out of range", port!! in 1..65535)
                }
            }
        }
    }

    @Test
    fun `recorded web ports really exist in the compose body`() {
        templates.forEach { template ->
            val missing = template.webPorts - template.containerPorts.toSet()
            assertTrue("template ${template.id} records web ports missing from its body: $missing", missing.isEmpty())
            assertEquals(
                "template ${template.id} repeats a web port",
                template.webPorts.size,
                template.webPorts.toSet().size,
            )
            assertEquals(
                "containerPorts must be de-duplicated and keep declaration order",
                template.containerPorts,
                template.containerPorts.distinct(),
            )
        }
    }

    @Test
    fun `ids are unique blank-free ascii lowercase and usable as project names`() {
        val ids = templates.map { it.id }
        assertEquals("template ids must be unique", ids.size, ids.toSet().size)
        templates.forEach { template ->
            val id = template.id
            assertTrue("template id must not be blank", id.isNotBlank())
            assertTrue("template id must be ASCII lowercase: $id", id.matches(Regex("[a-z0-9][a-z0-9._-]*")))
            assertEquals(
                "template id $id must survive ComposeNaming.sanitize unchanged (it is the default project name)",
                id,
                ComposeNaming.sanitize(id),
            )
            assertEquals("suggestedProjectName must default to id", id, template.suggestedProjectName)
        }
    }

    @Test
    fun `every template carries at least one note and no duplicates`() {
        templates.forEach { template ->
            assertTrue("template ${template.id} must have at least one note", template.noteIds.isNotEmpty())
            assertEquals(
                "template ${template.id} repeats a note",
                template.noteIds.size,
                template.noteIds.toSet().size,
            )
            template.noteIds.forEach { noteId ->
                assertNotNull(
                    "template ${template.id} references unknown note $noteId",
                    catalog.taxonomy.note(noteId),
                )
            }
        }
    }

    @Test
    fun `pid1 risk is only allowed on templates that disclose the workaround`() {
        templates.forEach { template ->
            val hasPid1Risk = template.services.any { it.hasPid1Risk() }
            if (hasPid1Risk) {
                assertTrue(
                    "template ${template.id} triggers PID1_RISK but does not reference the workaround note",
                    TemplateNotes.PID1_WORKAROUND in template.noteIds,
                )
            }
            assertTrue(
                "template ${template.id} passes /init as argv[0], which cannot work under proot",
                !template.services.any { it.resolvedArgv().firstOrNull()?.trim() == "/init" },
            )
        }
    }

    @Test
    fun `every built-in service opts into restart unless-stopped`() {
        // 模板里的服务都是「部署后指望一直跑」的常驻服务：没有策略时进程一退出容器就停在
        // STOPPED（含 Web UI 自身的「重启」——HA 网页端重启就是主进程 exit），用户得手动拉。
        // 详见 docs/template_authoring_guide.md §6.6。
        templates.forEach { template ->
            template.services.forEach { service ->
                assertEquals(
                    "template ${template.id} service ${service.name} must come back after an unexpected exit",
                    RestartPolicies.UNLESS_STOPPED,
                    service.restartPolicy,
                )
            }
        }
    }

    @Test
    fun `templates never mount volumes while the engine ignores them`() {
        templates.forEach { template ->
            assertTrue(
                "template ${template.id} declares volumes, but volumes are not mounted in this phase",
                template.services.flatMap { it.volumes }.isEmpty(),
            )
        }
    }

    @Test
    fun `error strings are not language-specific`() {
        // 解析期问题消息按约定用英文（与 EngineException / ComposeParseException 一致）
        val issues = TemplateCatalog.parse(
            "categories: []",
            mapOf("broken" to "services: {}\n"),
        ).issues
        assertTrue("sanity check: a broken template must produce issues", issues.isNotEmpty())
        issues.forEach { issue ->
            assertTrue(
                "issue messages must be English, got: ${issue.message}",
                issue.message.all { it.code < 128 },
            )
        }
    }

    // ------------------------------------------------------------ 容错：不完整的模板文件

    /** 最小分类学：故意不声明兜底分类 `other`，用它验证引擎会自己补出来。 */
    private val miniTaxonomy = """
        categories:
          - id: web
            order: 10
            name:
              en: Web
        risks:
          verified:
            en: Verified
        notes:
          port_direct:
            en: "open {urls}"
    """.trimIndent()

    /** 示例模板用的分类学：与 hello-world.yaml 的元数据配对（category: other + port_direct 提示）。 */
    private val exampleTaxonomy = """
        categories:
          - id: other
            order: 1000
            name:
              en: Other
              zh: 其他
              ru: Другое
              ja: その他
        risks:
          verified:
            en: Verified
          likely:
            en: Likely to work
          experimental:
            en: Experimental
        notes:
          port_direct:
            en: "Open {urls}"
            zh: "打开 {urls}"
            ru: "Открыть {urls}"
            ja: "{urls} を開く"
    """.trimIndent()

    @Test
    fun `a compose-only file becomes a template in the fallback category`() {
        val composeOnly = """
            services:
              filebrowser:
                image: filebrowser/filebrowser:latest
                environment:
                  - TZ=Asia/Shanghai
                ports:
                  - "8081:8081"
                command: /usr/local/bin/filebrowser -r /srv -d /database.db -a 0.0.0.0 -p 8081
        """.trimIndent()

        val catalog = TemplateCatalog.parse(miniTaxonomy, mapOf("filebrowser" to composeOnly))

        val template = catalog.byId("filebrowser")
        assertNotNull("dropping a compose-only file would lose the whole template", template)
        assertEquals(TemplateCatalog.FALLBACK_CATEGORY_ID, template!!.categoryId)
        assertEquals("filebrowser/filebrowser:latest", template.services.single().image)
        assertEquals(listOf(8081), template.containerPorts)
        assertEquals(
            "the file name must become the id so routing and default project names keep working",
            "filebrowser",
            template.id,
        )
        assertEquals(
            "without metadata the name falls back to the id so the card is still readable",
            "filebrowser",
            template.name(LocalizedText.DEFAULT_LANGUAGE),
        )
        assertEquals(TemplateRisk.EXPERIMENTAL, template.risk)
        assertTrue("without a premium flag the template must stay locked", template.premium)
        assertTrue(template.noteIds.isEmpty())
        assertTrue(template.webPorts.isEmpty())
        assertTrue(catalog.issues.any { it.kind == TemplateIssueKind.MISSING_METADATA })
        assertEquals(
            "the engine must synthesise the fallback category when the taxonomy lacks it",
            TemplateCatalog.FALLBACK_CATEGORY_ID,
            catalog.groups.single().category.id,
        )
    }

    @Test
    fun `a missing category is filed under the fallback category instead of dropping the template`() {
        val noCategory = """
            id: filebrowser
            name:
              en: Web file manager
            compose: |
              services:
                filebrowser:
                  image: filebrowser/filebrowser:latest
                  ports:
                    - "8081:8081"
        """.trimIndent()

        val catalog = TemplateCatalog.parse(miniTaxonomy, mapOf("filebrowser" to noCategory))

        val template = catalog.byId("filebrowser")
        assertNotNull(template)
        assertEquals(TemplateCatalog.FALLBACK_CATEGORY_ID, template!!.categoryId)
        assertEquals("Web file manager", template.name(LocalizedText.DEFAULT_LANGUAGE))
        assertTrue(
            "a missing category must be reported",
            catalog.issues.any { it.kind == TemplateIssueKind.MISSING_FIELD && "category" in it.message },
        )
    }

    @Test
    fun `an unknown category falls back instead of dropping the template`() {
        val unknownCategory = """
            id: filebrowser
            category: homelab
            compose: |
              services:
                filebrowser:
                  image: filebrowser/filebrowser:latest
        """.trimIndent()

        val catalog = TemplateCatalog.parse(miniTaxonomy, mapOf("filebrowser" to unknownCategory))

        val template = catalog.byId("filebrowser")
        assertNotNull(template)
        assertEquals(TemplateCatalog.FALLBACK_CATEGORY_ID, template!!.categoryId)
        assertTrue(catalog.issues.any { it.kind == TemplateIssueKind.UNKNOWN_CATEGORY })
    }

    @Test
    fun `a template without an id uses its file name`() {
        val noId = """
            category: web
            compose: |
              services:
                nginx:
                  image: nginx:alpine
                  ports:
                    - "80:80"
        """.trimIndent()

        val catalog = TemplateCatalog.parse(miniTaxonomy, mapOf("my-nginx" to noId))

        val template = catalog.byId("my-nginx")
        assertNotNull(template)
        assertEquals("my-nginx", template!!.id)
        assertEquals("web", template.categoryId)
        assertTrue(
            "using the file name must be reported",
            catalog.issues.any { it.kind == TemplateIssueKind.MISSING_FIELD && "id" in it.message },
        )
    }

    @Test
    fun `a file without services and without compose is still dropped`() {
        val catalog = TemplateCatalog.parse(miniTaxonomy, mapOf("junk" to "id: junk\nfoo: bar\n"))

        assertNull("a file with nothing to run cannot become a template", catalog.byId("junk"))
        assertTrue(catalog.issues.any { it.kind == TemplateIssueKind.MISSING_FIELD && "compose" in it.message })
    }

    @Test
    fun `an empty source set parses into an empty catalog with zero issues`() {
        // 本仓库 src/main/templates/ 为空：builtIn() 走的就是「空分类学 + 空模板」路径，
        // 必须零 issue、可安全预热
        val catalog = TemplateCatalog.parse("", emptyMap())

        assertTrue(catalog.templates.isEmpty())
        assertTrue(catalog.groups.isEmpty())
        assertEquals(0, catalog.free.size)
        assertEquals(
            "a blank taxonomy must not be reported as a problem, or the zero-issue gate fails",
            emptyList<TemplateIssue>(),
            catalog.issues,
        )
    }

    @Test
    fun `the hello-world example template parses cleanly`() {
        // 字段齐全的示例兼作编写文档（见 CONTRIBUTING.md）：必须始终能零 issue 解析
        val text = javaClass.getResourceAsStream("/examples/hello-world.yaml")!!
            .readBytes().toString(Charsets.UTF_8)

        val catalog = TemplateCatalog.parse(exampleTaxonomy, mapOf("hello-world" to text))

        assertEquals("the example must stay clean, or it stops being a valid reference",
            emptyList<TemplateIssue>(), catalog.issues)
        val template = catalog.byId("hello-world")
        assertNotNull(template)
        assertEquals("other", template!!.categoryId)
        assertEquals(listOf(8080), template.containerPorts)
        assertEquals(listOf(8080), template.webPorts)
        assertEquals(listOf("port_direct"), template.noteIds)
        assertEquals(false, template.premium)
        assertEquals(TemplateRisk.VERIFIED, template.risk)
        assertTrue("the example must include a non-blank compose body", template.yaml.isNotBlank())
        template.services.single().let { service ->
            assertEquals("nginx:alpine", service.image)
        }
    }

    /** compose 端口写法 `host:container` / `container` / `ip:host:container`（可带 `/tcp`）。 */
    private fun containerPort(entry: String): String {
        val withoutProtocol = entry.substringBefore('/')
        return withoutProtocol.substringAfterLast(':').trim()
    }
}
