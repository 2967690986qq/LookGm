// SkillDao.kt — 策略技能数据访问层
package com.gameai.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SkillDao {

    @Query("SELECT * FROM skills WHERE isEnabled = 1 ORDER BY category, name")
    fun getEnabledSkills(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY category, name")
    fun getAllSkills(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE gameName = :gameName AND isEnabled = 1 ORDER BY category, name")
    fun getSkillsByGame(gameName: String): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE heroName = :heroName AND isEnabled = 1")
    fun getSkillsByHero(heroName: String): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE category = :category AND isEnabled = 1")
    fun getSkillsByCategory(category: String): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE isEnabled = 1")
    suspend fun getEnabledSkillsSnapshot(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE gameName = :gameName AND isEnabled = 1")
    suspend fun getSkillsByGameSnapshot(gameName: String): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE heroName = :heroName AND isEnabled = 1")
    suspend fun getSkillsByHeroSnapshot(heroName: String): List<SkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: SkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkills(skills: List<SkillEntity>)

    @Update
    suspend fun updateSkill(skill: SkillEntity)

    @Query("UPDATE skills SET isEnabled = :enabled WHERE skillId = :skillId")
    suspend fun setSkillEnabled(skillId: String, enabled: Boolean)

    @Delete
    suspend fun deleteSkill(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE skillId = :skillId")
    suspend fun deleteSkillById(skillId: String)

    @Query("SELECT COUNT(*) FROM skills WHERE isEnabled = 1")
    suspend fun getEnabledSkillCount(): Int

    @Query("SELECT COUNT(*) FROM skills WHERE isEnabled = 1 AND source = 'builtin'")
    suspend fun getBuiltinSkillCount(): Int
}
