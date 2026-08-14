package com.psybergate.recruitment.repository;

import com.psybergate.recruitment.domain.Difficulty;
import com.psybergate.recruitment.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    @Query("SELECT DISTINCT q FROM Question q JOIN q.tags t WHERE t.name = :tagName")
    List<Question> findByTagName(@Param("tagName") String tagName);

    @Query("SELECT DISTINCT q FROM Question q LEFT JOIN q.tags t " +
            "WHERE (:tagName IS NULL OR t.name = :tagName) " +
            "AND (:difficulty IS NULL OR q.difficulty = :difficulty)")
    List<Question> findByTagNameAndDifficulty(@Param("tagName") String tagName,
                                               @Param("difficulty") Difficulty difficulty);
}
