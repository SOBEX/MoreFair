package de.kaliburg.morefair.game.round.services.repositories;

import de.kaliburg.morefair.game.round.model.RoundEntity;
import de.kaliburg.morefair.game.season.model.SeasonEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RoundRepository extends JpaRepository<RoundEntity, Long> {

  @Query("select r from RoundEntity r where r.number = :number")
  Optional<RoundEntity> findByNumber(@Param("number") Integer number);

  @Query("select r from RoundEntity r where r.seasonId = :seasonId and r.number = :number")
  Optional<RoundEntity> findBySeasonAndNumber(@Param("seasonId") Long seasonId,
      @Param("number") Integer number);

  Optional<RoundEntity> findNewestRoundOfSeason(SeasonEntity currentSeason);
}
