package net.pantasystem.milktea.data.infrastructure.note.wordmute

import androidx.room.*
import net.pantasystem.milktea.model.note.muteword.FilterConditionType
import net.pantasystem.milktea.model.note.muteword.WordFilterConfig

@Entity(
    tableName = "word_filter_condition"
)
data class WordFilterConditionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L
)

@Entity(
    tableName = "word_filter_regex_condition",
    foreignKeys = [
        ForeignKey(
            entity = WordFilterConditionRecord::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId")]
)
data class WordFilterConditionRegexRecord(
    @ColumnInfo(name = "pattern")
    val pattern: String,

    @ColumnInfo(name = "parentId")
    @PrimaryKey(autoGenerate = false)
    val parentId: Long = 0L
)

@Entity(
    tableName = "word_filter_word_condition",
    foreignKeys = [
        ForeignKey(
            entity = WordFilterConditionRecord::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId")]
)
data class WordFilterConditionWordRecord(
    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "parentId")
    val parentId: Long,

    @ColumnInfo(name = "id")
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
)

data class WordFilterConditionRelated(
    @Embedded val condition: WordFilterConditionRecord,
    @Relation(parentColumn = "id", entityColumn = "parentId")
    val regexRecord: WordFilterConditionRegexRecord?,
    @Relation(parentColumn = "id", entityColumn = "parentId")
    val words: List<WordFilterConditionWordRecord>
)

fun List<WordFilterConditionRelated>.toModel(): WordFilterConfig {
    return WordFilterConfig(map { related ->
        if (related.regexRecord == null) {
            FilterConditionType.Normal(related.words.map { it.word })
        } else {
            FilterConditionType.Regex(related.regexRecord.pattern)
        }
    })
}
