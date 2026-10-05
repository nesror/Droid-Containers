package cn.yzapp.androidcontainer.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Query("SELECT * FROM images ORDER BY createdAt DESC")
    fun observeImages(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE ref = :ref")
    suspend fun findImage(ref: String): ImageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImage(image: ImageEntity)

    @Query("DELETE FROM images WHERE ref = :ref")
    suspend fun deleteImage(ref: String)

    @Query("SELECT * FROM containers ORDER BY createdAt DESC")
    fun observeContainers(): Flow<List<ContainerEntity>>

    @Query("SELECT * FROM containers WHERE id = :id")
    suspend fun findContainer(id: String): ContainerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContainer(container: ContainerEntity)

    @Query("DELETE FROM containers WHERE id = :id")
    suspend fun deleteContainer(id: String)

    @Query("SELECT COUNT(*) FROM containers WHERE status = 'RUNNING'")
    fun observeRunningCount(): Flow<Int>

    // ------------------------------------------------------------ compose 编排（M7）

    @Query("SELECT * FROM compose_projects ORDER BY createdAt DESC")
    fun observeComposeProjects(): Flow<List<ComposeProjectEntity>>

    @Query("SELECT * FROM compose_projects WHERE id = :id")
    suspend fun findComposeProject(id: String): ComposeProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComposeProject(project: ComposeProjectEntity)

    @Query("DELETE FROM compose_projects WHERE id = :id")
    suspend fun deleteComposeProject(id: String)

    @Query("SELECT * FROM containers WHERE projectId = :projectId ORDER BY createdAt ASC")
    suspend fun findContainersByProject(projectId: String): List<ContainerEntity>

    @Query("SELECT * FROM containers WHERE projectId = :projectId AND serviceName = :serviceName LIMIT 1")
    suspend fun findContainerByService(projectId: String, serviceName: String): ContainerEntity?
}
