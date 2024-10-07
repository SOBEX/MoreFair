package de.kaliburg.morefair.game.round;

import static de.kaliburg.morefair.events.types.LadderEventTypes.BUY_AUTO_PROMOTE;
import static de.kaliburg.morefair.events.types.LadderEventTypes.BUY_BIAS;
import static de.kaliburg.morefair.events.types.LadderEventTypes.BUY_MULTI;
import static de.kaliburg.morefair.events.types.LadderEventTypes.PROMOTE;
import static de.kaliburg.morefair.events.types.LadderEventTypes.THROW_VINEGAR;

import de.kaliburg.morefair.api.FairController;
import de.kaliburg.morefair.api.LadderController;
import de.kaliburg.morefair.api.utils.WsUtils;
import de.kaliburg.morefair.events.Event;
import de.kaliburg.morefair.events.types.LadderEventTypes;
import de.kaliburg.morefair.game.round.dto.HeartbeatDto;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@EnableScheduling
@RequiredArgsConstructor
public class LadderCalculator {

  private static final double NANOS_IN_SECONDS = TimeUnit.SECONDS.toNanos(1);
  private final LadderService ladderService;
  private final WsUtils wsUtils;
  private final LadderUtils ladderUtils;
  private final HeartbeatDto heartbeat = new HeartbeatDto();
  private long lastTimeMeasured = System.nanoTime();

  @Scheduled(initialDelay = 1000, fixedRate = 1000)
  public void update() {
    // Reset the Heartbeat
    try {
      ladderService.getLadderSemaphore().acquire();
      try {
        // Process and filter all events since the last Calculation Step
        handlePlayerEvents();

        // Calculate Time passed
        long currentNanos = System.nanoTime();
        double deltaSec = Math.max((currentNanos - lastTimeMeasured) / NANOS_IN_SECONDS,
            1.0d);
        lastTimeMeasured = currentNanos;

        // Otherwise, just send the default Heartbeat-Tick
        heartbeat.setDelta(deltaSec);
        wsUtils.convertAndSendToTopic(FairController.TOPIC_TICK_DESTINATION, heartbeat);

        // Calculate Ladder yourself
        Collection<LadderEntity> ladders = ladderService.getCurrentLadderMap().values();
        List<CompletableFuture<Void>> futures = ladders.stream()
            .map(ladder -> CompletableFuture.runAsync(() -> calculateLadder(ladder, deltaSec)))
            .toList();
        try {
          CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (ExecutionException | InterruptedException e) {
          log.error(e.getMessage());
          e.printStackTrace();
        }
      } finally {
        ladderService.getLadderSemaphore().release();
      }
    } catch (InterruptedException e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

  private void handlePlayerEvents() {
    try {
      ladderService.getEventSemaphore().acquire();
      try {
        for (int i = 1; i <= ladderService.getCurrentLadderMap().size(); i++) {
          // Handle the events since the last update
          LadderEntity ladder = ladderService.getCurrentLadderMap().get(i);
          List<Event<LadderEventTypes>> events = ladderService.getEventMap()
              .get(ladder.getNumber());
          List<Event<LadderEventTypes>> eventsToBeRemoved = new ArrayList<>();
          for (Event<LadderEventTypes> e : events) {
            if (BUY_BIAS.equals(e.getEventType())) {
              if (!ladderService.buyBias(e, ladder)) {
                eventsToBeRemoved.add(e);
              }
            } else if (BUY_MULTI.equals(e.getEventType())) {
              if (!ladderService.buyMulti(e, ladder)) {
                eventsToBeRemoved.add(e);
              }
            } else if (PROMOTE.equals(e.getEventType())) {
              if (!ladderService.promote(e, ladder)) {
                eventsToBeRemoved.add(e);
              }
            } else if (THROW_VINEGAR.equals(e.getEventType())) {
              if (!ladderService.throwVinegar(e, ladder)) {
                eventsToBeRemoved.add(e);
              }
            } else if (BUY_AUTO_PROMOTE.equals(e.getEventType())) {
              if (!ladderService.buyAutoPromote(e, ladder)) {
                eventsToBeRemoved.add(e);
              }
            }
          }
          for (Event<LadderEventTypes> e : eventsToBeRemoved) {
            events.remove(e);
          }
        }
        ladderService.getEventMap().values().forEach(List::clear);
      } finally {
        ladderService.getEventSemaphore().release();
      }
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

  private void calculateLadder(LadderEntity ladder, double delta) {
    List<RankerEntity> rankers = ladder.getRankers();
    rankers.sort(Comparator.comparing(RankerEntity::getPoints).reversed());

    for (int i = 0; i < rankers.size(); i++) {
      RankerEntity currentRanker = rankers.get(i);
      currentRanker.setRank(i + 1);
      // if the ranker is currently still on the ladder
      if (currentRanker.isGrowing()) {
        // Calculating points & Power
        if (currentRanker.getRank() != 1) {
          currentRanker.addPower(
              (i + currentRanker.getBias()) * currentRanker.getMultiplier(), delta);
        }
        currentRanker.addPoints(currentRanker.getPower(), delta);

        // Calculating Vinegar based on Grapes count
        if (currentRanker.getRank() != 1) {
          currentRanker.addVinegar(currentRanker.getGrapes(), delta);
        }
        if (currentRanker.getRank() == 1 && ladderUtils.isLadderPromotable(ladder)) {
          currentRanker.mulVinegar(0.9975, delta);
        }

        for (int j = i - 1; j >= 0; j--) {
          // If one of the already calculated Rankers have less points than this ranker
          // swap these in the list... This way we keep the list sorted, theoretically
          if (currentRanker.getPoints().compareTo(rankers.get(j).getPoints()) > 0) {
            // Move 1 Position up and move the ranker there 1 Position down

            // Move other Ranker 1 Place down
            RankerEntity temp = rankers.get(j);
            temp.setRank(j + 2);
            if (temp.isGrowing() && temp.getMultiplier() > 1) {
              temp.setGrapes(temp.getGrapes().add(BigInteger.ONE));
            }
            rankers.set(j + 1, temp);

            // Move this Ranker 1 Place up
            currentRanker.setRank(j + 1);
            rankers.set(j, currentRanker);
          } else {
            break;
          }
        }
      }
    }
    // Ranker on Last Place gains 1 Grape, even if hes also in first at the same time (ladder of 1)
    if (rankers.size() >= 1) {
      RankerEntity lastRanker = rankers.get(rankers.size() - 1);
      if (lastRanker.isGrowing()) {
        lastRanker.addGrapes(BigInteger.valueOf(2), delta);
      }
    }

    if (rankers.size() >= 1 && (rankers.get(0).isAutoPromote() || ladder.getTypes()
        .contains(LadderType.FREE_AUTO)) && rankers.get(0).isGrowing()
        && ladderUtils.isLadderPromotable(ladder)) {
      ladderService.addEvent(ladder.getNumber(),
          new Event<>(PROMOTE, rankers.get(0).getAccount().getId()));
    }

    if (ladder.getRound().getNumber() == 300 && !rankers.isEmpty()) {

      var now = OffsetDateTime.now();

      if (!ladder.getTypes().contains(LadderType.CHEAP)
          && now.isAfter(ladder.getRound().getCreatedOn().plusHours(1))) {
        ladder.getTypes().add(LadderType.CHEAP);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_2)
          && now.isAfter(ladder.getRound().getCreatedOn().plusDays(1))) {
        ladder.getTypes().add(LadderType.CHEAP_2);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_3)
          && now.isAfter(ladder.getRound().getCreatedOn().plusDays(2))) {
        ladder.getTypes().add(LadderType.CHEAP_3);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_4)
          && now.isAfter(ladder.getRound().getCreatedOn().plusDays(3))) {
        ladder.getTypes().add(LadderType.CHEAP_4);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_5)
          && rankers.get(0).getPoints().compareTo(BigInteger.valueOf(10_000_000_000_000L)) >= 0) {
        ladder.getTypes().add(LadderType.CHEAP_5);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_6)
          && rankers.get(0).getPoints().compareTo(BigInteger.valueOf(100_000_000_000_000L)) >= 0) {
        ladder.getTypes().add(LadderType.CHEAP_6);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_7)
          && rankers.get(0).getPoints().compareTo(BigInteger.valueOf(1_000_000_000_000_000L))
          >= 0) {
        ladder.getTypes().add(LadderType.CHEAP_7);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_8)
          && now.isAfter(ladder.getRound().getCreatedOn().plusDays(4))) {
        ladder.getTypes().add(LadderType.CHEAP_8);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_9)
          && now.isAfter(ladder.getRound().getCreatedOn().plusDays(5))) {
        ladder.getTypes().add(LadderType.CHEAP_9);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }

      if (!ladder.getTypes().contains(LadderType.CHEAP_10)
          && !rankers.get(0).isGrowing()) {
        ladder.getTypes().add(LadderType.CHEAP_10);

        Event<LadderEventTypes> e = new Event<>(LadderEventTypes.UPDATE_TYPES, ladder.getId(),
            ladder.getTypes());
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), e);
      }


    }

  }
}



