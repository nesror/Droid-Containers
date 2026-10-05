package cn.yzapp.androidcontainer.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ImageEntity::class, ContainerEntity::class, ComposeProjectEntity::class],
    version = 5,
    // schema JSON 入库（schemas/）+ 编译期导出：升级漏写迁移会在构建/测试期暴露，
    // 而不是把用户库存（镜像 rootfs 路径按百 MB 计）静默清库（审查 P1-14）
    exportSchema = true,
)
abstract class InventoryDatabase : RoomDatabase() {

    abstract fun inventoryDao(): InventoryDao

    companion object {

        /**
         * v1 → v2：新增 compose_projects 表，containers 追加编排字段。
         *
         * 用增量迁移而不是清库重建：已拉取镜像的体积按百 MB 计，升级即失效的代价过高。
         * 新增列全部可空，故 ALTER TABLE 无需回填默认值。
         */
        val MIGRATION_1_2: Migration = Migration(1, 2) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `compose_projects` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `yamlContent` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("ALTER TABLE `containers` ADD COLUMN `projectId` TEXT")
            db.execSQL("ALTER TABLE `containers` ADD COLUMN `serviceName` TEXT")
            db.execSQL("ALTER TABLE `containers` ADD COLUMN `cmdJson` TEXT")
            db.execSQL("ALTER TABLE `containers` ADD COLUMN `envJson` TEXT")
        }

        /**
         * v2 → v3：containers 追加 dockerId 列（M9 Docker 兼容层，方案 m8_m9 §4.2-1）。
         * 可空列，无回填；旧记录首次被 Docker API 访问时惰性生成。
         */
        val MIGRATION_2_3: Migration = Migration(2, 3) { db ->
            db.execSQL("ALTER TABLE `containers` ADD COLUMN `dockerId` TEXT")
        }

        /**
         * v3 → v4：containers 追加 restartPolicy 列（compose `restart` 策略，方案 §3.1）。
         * 可空列，无回填；null = 未声明，按 `no`（自然退出保持停止）处理。
         */
        val MIGRATION_3_4: Migration = Migration(3, 4) { db ->
            db.execSQL("ALTER TABLE `containers` ADD COLUMN `restartPolicy` TEXT")
        }

        /**
         * v4 → v5：containers 追加 desiredRunning 列（进程重启自恢复，docker daemon
         * 重启恢复语义）。存量容器回填 0（不自动恢复），用户下次手动启动后生效。
         */
        val MIGRATION_4_5: Migration = Migration(4, 5) { db ->
            db.execSQL(
                "ALTER TABLE `containers` ADD COLUMN `desiredRunning` INTEGER NOT NULL DEFAULT 0",
            )
        }

        fun create(context: Context): InventoryDatabase =
            Room.databaseBuilder(context.applicationContext, InventoryDatabase::class.java, "inventory.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // 不配 fallbackToDestructiveMigration（审查 P1-14）：升级路径缺迁移时
                // 宁可崩溃暴露问题，也不能静默 dropAllTables 清掉镜像/容器/编排库存。
                // 新版本号必须配套 Migration（schema JSON 在 schemas/ 下可 diff）
                .build()
    }
}
