package de.kaliburg.morefair.game.ladder.services.impl;

import static de.kaliburg.morefair.events.types.LadderEventType.BUY_AUTO_PROMOTE;
import static de.kaliburg.morefair.events.types.LadderEventType.BUY_BIAS;
import static de.kaliburg.morefair.events.types.LadderEventType.BUY_MULTI;
import static de.kaliburg.morefair.events.types.LadderEventType.PROMOTE;
import static de.kaliburg.morefair.events.types.LadderEventType.THROW_VINEGAR;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.kaliburg.morefair.FairConfig;
import de.kaliburg.morefair.account.model.AccountEntity;
import de.kaliburg.morefair.account.services.AccountService;
import de.kaliburg.morefair.api.AccountController;
import de.kaliburg.morefair.api.LadderController;
import de.kaliburg.morefair.api.RoundController;
import de.kaliburg.morefair.api.utils.WsUtils;
import de.kaliburg.morefair.chat.model.ChatEntity;
import de.kaliburg.morefair.chat.model.types.ChatType;
import de.kaliburg.morefair.chat.services.ChatService;
import de.kaliburg.morefair.chat.services.MessageService;
import de.kaliburg.morefair.core.concurrency.CriticalRegion;
import de.kaliburg.morefair.events.Event;
import de.kaliburg.morefair.events.data.VinegarData;
import de.kaliburg.morefair.events.types.AccountEventTypes;
import de.kaliburg.morefair.events.types.LadderEventType;
import de.kaliburg.morefair.events.types.RoundEventTypes;
import de.kaliburg.morefair.game.UpgradeUtils;
import de.kaliburg.morefair.game.ladder.model.LadderEntity;
import de.kaliburg.morefair.game.ladder.model.LadderType;
import de.kaliburg.morefair.game.ladder.services.LadderEventService;
import de.kaliburg.morefair.game.ladder.services.LadderService;
import de.kaliburg.morefair.game.ranker.model.RankerEntity;
import de.kaliburg.morefair.game.ranker.services.RankerService;
import de.kaliburg.morefair.game.ranker.services.utils.RankerUtilsService;
import de.kaliburg.morefair.game.round.model.RoundEntity;
import de.kaliburg.morefair.game.round.model.UnlocksEntity;
import de.kaliburg.morefair.game.round.model.type.RoundType;
import de.kaliburg.morefair.game.round.services.RoundService;
import de.kaliburg.morefair.game.round.services.UnlocksService;
import de.kaliburg.morefair.game.round.services.utils.UnlocksUtilsService;
import de.kaliburg.morefair.game.season.model.AchievementsEntity;
import de.kaliburg.morefair.game.season.services.AchievementsService;
import de.kaliburg.morefair.statistics.services.StatisticsService;
import de.kaliburg.morefair.utils.FormattingUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class LadderEventServiceImpl implements LadderEventService {


  private final AccountService accountService;
  private final ChatService chatService;
  private final MessageService messageService;
  private final RankerService rankerService;
  private final LadderService ladderService;
  private final RankerUtilsService ladderUtilsService;
  private final RoundService roundService;
  private final UnlocksService unlocksService;
  private final UnlocksUtilsService unlocksUtilsService;
  private final AchievementsService achievementsService;
  private final StatisticsService statisticsService;
  private final WsUtils wsUtils;
  private final UpgradeUtils upgradeUtils;
  private final FairConfig fairConfig;
  private final Gson gson;
  private final CriticalRegion semaphore = new CriticalRegion(1);
  private final Map<Integer, List<Event<LadderEventType>>> eventMap = new HashMap<>();

  @Override
  public void handleEvents() throws InterruptedException {
    try (var ignored = semaphore.enter()) {
      RoundEntity currentRound = roundService.getCurrentRound();

      for (int i = 1; i <= ladderService.findAllByRound(currentRound).size(); i++) {
        LadderEntity ladder = ladderService.findCurrentLadderWithNumber(i).orElseThrow();
        List<Event<LadderEventType>> events =
            eventMap.computeIfAbsent(ladder.getNumber(), k -> new ArrayList<>());
        List<Event<LadderEventType>> eventsToBeRemoved = new ArrayList<>();
        for (Event<LadderEventType> e : events) {
          if (BUY_BIAS.equals(e.getEventType())) {
            if (!buyBias(e, ladder)) {
              eventsToBeRemoved.add(e);
            }
          } else if (BUY_MULTI.equals(e.getEventType())) {
            if (!buyMulti(e, ladder)) {
              eventsToBeRemoved.add(e);
            }
          } else if (PROMOTE.equals(e.getEventType())) {
            if (!promote(e, ladder)) {
              eventsToBeRemoved.add(e);
            }
          } else if (THROW_VINEGAR.equals(e.getEventType())) {
            if (!throwVinegar(e, ladder)) {
              eventsToBeRemoved.add(e);
            }
          } else if (BUY_AUTO_PROMOTE.equals(e.getEventType())) {
            if (!buyAutoPromote(e, ladder)) {
              eventsToBeRemoved.add(e);
            }
          }
        }
        for (Event<LadderEventType> e : eventsToBeRemoved) {
          events.remove(e);
        }
      }
      eventMap.values().forEach(List::clear);
    }
  }

  /**
   * Adds an event to the list of events inside the eventMap. This calls a semaphore and should
   * thereby only be done by the Controllers/API.
   *
   * @param event the event that gets added to the eventMap
   */
  public void addEvent(int ladderNumber, Event<LadderEventType> event) {
    try (var ignored = semaphore.enter()) {
      List<Event<LadderEventType>> events =
          eventMap.computeIfAbsent(ladderNumber, k -> new ArrayList<>());
      events.add(event);
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
  }


  /**
   * Buy Bias for the active ranker of an account on a specific ladder.
   *
   * @param event  the event that contains the information for the buy
   * @param ladder the ladder the ranker is on
   * @return if the ranker can buy bias
   */
  private boolean buyBias(Event<LadderEventType> event, LadderEntity ladder) {
    try {
      AccountEntity account = accountService.findById(event.getAccountId())
          .orElseThrow();
      RankerEntity ranker = rankerService.findHighestActiveRankerOfAccount(account)
          .orElseThrow();

      if (!ladder.getId().equals(ranker.getLadderId())) {
        return false;
      }

      BigInteger cost = upgradeUtils.buyUpgradeCost(ladder.getScaling(), ranker.getBias(),
          ladder.getTypes());
      if (ranker.getPoints().compareTo(cost) >= 0) {
        statisticsService.recordBias(ranker, ladder, roundService.getCurrentRound());
        ranker.setPoints(BigInteger.ZERO);
        ranker.setBias(ranker.getBias() + 1);
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), event);
        return true;
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    return false;
  }

  /**
   * Buy multi for the active ranker of an account on a specific ladder.
   *
   * @param event  the event that contains the information for the buy
   * @param ladder the ladder the ranker is on
   * @return if the ranker can buy multi
   */
  private boolean buyMulti(Event<LadderEventType> event, LadderEntity ladder) {
    try {
      AccountEntity account = accountService.findById(event.getAccountId())
          .orElseThrow();
      RankerEntity ranker = rankerService.findHighestActiveRankerOfAccount(account)
          .orElseThrow();

      if (!ladder.getId().equals(ranker.getLadderId())) {
        return false;
      }

      BigInteger cost = upgradeUtils.buyUpgradeCost(ladder.getScaling(), ranker.getMultiplier(),
          ladder.getTypes());
      if (ranker.getPower().compareTo(cost) >= 0) {
        statisticsService.recordMulti(ranker, ladder, roundService.getCurrentRound());
        ranker.setPoints(BigInteger.ZERO);
        ranker.setPower(BigInteger.ZERO);
        ranker.setBias(0);
        ranker.setMultiplier(ranker.getMultiplier() + 1);
        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), event);
        return true;
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    return false;
  }

  /**
   * Buy auto-promote for the active ranker of an account on a specific ladder.
   *
   * @param event  the event that contains the information for the buy
   * @param ladder the ladder the ranker is on
   * @return if the ranker can buy auto-promote
   */
  boolean buyAutoPromote(Event<LadderEventType> event, LadderEntity ladder) {
    try {
      AccountEntity account = accountService.findById(event.getAccountId())
          .orElseThrow();
      RankerEntity ranker = rankerService.findHighestActiveRankerOfAccount(account)
          .orElseThrow();

      if (!ladder.getId().equals(ranker.getLadderId())) {
        return false;
      }

      BigInteger cost = upgradeUtils.buyAutoPromoteCost(roundService.getCurrentRound(), ladder,
          ranker.getRank());
      if (ladder.getTypes().contains(LadderType.FREE_AUTO)) {
        ranker.setAutoPromote(true);

        wsUtils.convertAndSendToUser(account.getUuid(), LadderController.PRIVATE_EVENTS_DESTINATION,
            event);

        return true;
      }

      if (ladderUtilsService.canBuyAutoPromote(ranker)) {
        statisticsService.recordAutoPromote(ranker, ladder, roundService.getCurrentRound());
        ranker.setGrapes(ranker.getGrapes().subtract(cost));
        ranker.setAutoPromote(true);
        wsUtils.convertAndSendToUser(account.getUuid(), LadderController.PRIVATE_EVENTS_DESTINATION,
            event);
        return true;
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    return false;
  }

  /**
   * Promote the active ranker of an account on a specific ladder.
   *
   * @param event  the event that contains the information for the buy
   * @param ladder the ladder the ranker is on
   * @return if the ranker can promote
   */
  boolean promote(Event<LadderEventType> event, LadderEntity ladder) {
    try {
      AccountEntity account = accountService.findById(event.getAccountId())
          .orElseThrow();
      RankerEntity ranker = rankerService.findHighestActiveRankerOfAccount(account)
          .orElseThrow();

      if (!ladder.getId().equals(ranker.getLadderId())) {
        return false;
      }

      if (ladderUtilsService.canPromote(ranker)) {
        statisticsService.recordPromote(ranker, ladder, roundService.getCurrentRound());
        log.info("[L{}] Promotion for {} (#{})", ladder.getNumber(), account.getDisplayName(),
            account.getId());
        ranker.setGrowing(false);

        RankerEntity newRanker = rankerService.createRankerOnLadder(account, ladder.getNumber() + 1)
            .orElseThrow();
        newRanker.setVinegar(ranker.getVinegar());
        newRanker.setGrapes(ranker.getGrapes());
        LadderEntity newLadder = ladderService.findLadderById(newRanker.getLadderId())
            .orElseThrow();

        // Auto-Ladder
        int number = Math.floorDiv(newLadder.getNumber(), 2) - 2;
        LadderEntity autoLadder = ladderService.findCurrentLadderWithNumber(number)
            .orElse(null);
        if (autoLadder != null && !autoLadder.getTypes().contains(LadderType.FREE_AUTO)
            && !autoLadder.getTypes().contains(LadderType.NO_AUTO)) {
          autoLadder.getTypes().add(LadderType.FREE_AUTO);
          Event<LadderEventType> e = new Event<>(LadderEventType.UPDATE_TYPES, account.getId(),
              autoLadder.getTypes());
          wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
              autoLadder.getNumber(), e);
        }

        UnlocksEntity unlocks = unlocksService.findOrCreateByAccountInCurrentRound(account.getId());
        AchievementsEntity achievements =
            achievementsService.findOrCreateByAccountInCurrentSeason(account.getId());

        // Special_100 Logic for moving the Ladder to 50 after finishing Ladder 100
        RoundEntity round = roundService.getCurrentRound();
        if (round.getTypes().contains(RoundType.SPECIAL_100)
            && newLadder.getTypes().contains(LadderType.END)) {
          LadderEntity assholeLadder =
              ladderService.findCurrentLadderWithNumber(round.getModifiedBaseAssholeLadder())
                  .orElseThrow();

          assholeLadder.getTypes().remove(LadderType.DEFAULT);
          assholeLadder.getTypes().add(LadderType.ASSHOLE);

          List<RankerEntity> assholeRankers = rankerService.findAllByLadderId(
              assholeLadder.getId());
          assholeRankers.forEach(r -> {
            AccountEntity a = accountService.findById(r.getAccountId()).orElseThrow();
            if (!r.isGrowing()) {
              achievements.setPressedAssholeButtons(achievements.getPressedAssholeButtons() + 1);
              unlocks.setPressedAssholeButton(true);
            }
            unlocks.setReachedAssholeLadder(true);
          });

          Event<LadderEventType> e = new Event<>(LadderEventType.UPDATE_TYPES, account.getId(),
              assholeLadder.getTypes());
          wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
              assholeLadder.getNumber(), e);
        }

        // Unlocks
        if (!unlocks.getUnlockedAutoPromote()
            && newLadder.getNumber() >= fairConfig.getAutoPromoteLadder()) {
          unlocks.setUnlockedAutoPromote(true);
        }
        if (!unlocks.getReachedBaseAssholeLadder()
            && newLadder.getNumber() >= roundService.getCurrentRound()
            .getModifiedBaseAssholeLadder()) {
          unlocks.setReachedBaseAssholeLadder(true);
        }
        if (!unlocks.getReachedAssholeLadder()
            && newLadder.getTypes().contains(LadderType.ASSHOLE)) {
          unlocks.setReachedAssholeLadder(true);
        }
        if (!unlocks.getPressedAssholeButton()
            && ladder.getTypes().contains(LadderType.ASSHOLE)) {
          unlocks.setPressedAssholeButton(true);
          achievements.setPressedAssholeButtons(achievements.getPressedAssholeButtons() + 1);
        }

        // Rewards for finishing first / at the top
        List<RankerEntity> newRankers =
            rankerService.findAllByLadderId(newLadder.getId());
        if (newRankers.size() <= 1) {
          newRanker.setAutoPromote(true);
          newRanker.setVinegar(
              newRanker.getVinegar().multiply(BigInteger.valueOf(newLadder.getWinningMultiplier()))
                  .divide(BigInteger.TEN));
        }

        newRanker.setGrapes(
            newRanker.getGrapes().add(ladderUtilsService.getWinningGrapes(newLadder))
        );

        wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
            ladder.getNumber(), event);
        wsUtils.convertAndSendToUser(account.getUuid(),
            AccountController.PRIVATE_EVENTS_DESTINATION, new Event<>(
                AccountEventTypes.INCREASE_HIGHEST_LADDER, account.getId(), newLadder.getNumber()
            ));
        account = accountService.save(account);

        unlocksService.save(unlocks);
        achievementsService.save(achievements);

        // Logic for the Asshole-Ladder
        if (ladder.getTypes().contains(LadderType.ASSHOLE) && unlocks.getPressedAssholeButton()) {
          JsonObject object1 = new JsonObject();
          object1.addProperty("u", account.getDisplayName());
          object1.addProperty("id", account.getId());
          object1.addProperty("i", 0);

          AccountEntity broadCaster = accountService.findBroadcaster().orElseThrow();
          JsonObject object2 = new JsonObject();
          object2.addProperty("u", broadCaster.getDisplayName());
          object2.addProperty("id", broadCaster.getId());
          object2.addProperty("i", 20);

          String metadataString = gson.toJson(new JsonObject[]{object1, object2});

          ChatEntity chat = chatService.find(ChatType.SYSTEM);

          messageService.create(broadCaster, chat, FormattingUtils.format(
              "{@} was welcomed by {@}. They are the {} lucky initiate for the {} big ritual.",
              FormattingUtils.ordinal(newRankers.size()),
              FormattingUtils.ordinal(roundService.getCurrentRound().getNumber())
          ), metadataString);

          int neededAssholesForReset = roundService.getCurrentRound().getAssholesForReset();
          int assholeCount = newRankers.size();

          // Is it time to reset the game
          if (assholeCount >= neededAssholesForReset || round.getTypes()
              .contains(RoundType.SPECIAL_100)) {
            wsUtils.convertAndSendToTopic(RoundController.TOPIC_EVENTS_DESTINATION,
                new Event<>(RoundEventTypes.RESET, account.getId()));

            LadderEntity firstLadder = ladderService.findCurrentLadderWithNumber(1)
                .orElseThrow();
            List<RankerEntity> firstRankers = rankerService.findAllByLadderId(firstLadder.getId());
            for (RankerEntity r : firstRankers) {
              var u = unlocksService.findOrCreateByAccountInCurrentRound(r.getAccountId());
              var a = achievementsService.findOrCreateByAccountInCurrentSeason(r.getAccountId());
              a.setAssholePoints(
                  a.getAssholePoints() + unlocksUtilsService.calculateAssholePoints(u)
              );

              unlocksService.save(u);
              achievementsService.save(a);
            }

            roundService.closeCurrentRound();
            ladderService.reloadLadders();
            rankerService.reloadRankers();
          }
        }
        return true;
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }

    return false;
  }


  /**
   * Throw vinegar as the active ranker of an account on a specific ladder.
   *
   * @param event  the event that contains the information for the buy
   * @param ladder the ladder the ranker is on
   * @return if the ranker can throw vinegar at the rank-1-ranker
   */
  boolean throwVinegar(Event<LadderEventType> event, LadderEntity ladder) {
    try {
      AccountEntity rankerAccount = accountService.findById(event.getAccountId()).orElseThrow();
      RankerEntity ranker = rankerService.findHighestActiveRankerOfAccount(rankerAccount)
          .orElseThrow();

      if (!ladder.getId().equals(ranker.getLadderId())) {
        return false;
      }

      List<RankerEntity> rankers = rankerService.findAllByLadderId(ladder.getId());

      RankerEntity target = rankers.get(0);
      AccountEntity targetAccount = accountService.findById(target.getAccountId()).orElseThrow();

      if (target.isAutoPromote() || ladder.getTypes().contains(LadderType.FREE_AUTO)) {
        log.info("[L{}] {} (#{}) tried to throw Vinegar at {} (#{}), but they had Auto-Promote!",
            ladder.getNumber(), rankerAccount.getDisplayName(), rankerAccount.getId(),
            targetAccount.getDisplayName(), targetAccount.getId());
        return false;
      }

      if (ladderUtilsService.canThrowVinegarAt(ranker, target)) {
        statisticsService.recordVinegarThrow(ranker, target, ladder,
            roundService.getCurrentRound());
        BigInteger rankerVinegar = ranker.getVinegar();
        BigInteger targetVinegar = target.getVinegar();

        log.info("[L{}] {} (#{}) is using their {} Vinegar on {} (#{}) with {} Vinegar",
            ladder.getNumber(), rankerAccount.getDisplayName(), rankerAccount.getId(),
            rankerVinegar,
            targetAccount.getDisplayName(), targetAccount.getId(), targetVinegar);

        VinegarData data = new VinegarData(rankerVinegar.toString(), targetAccount.getId());
        if (targetVinegar.compareTo(rankerVinegar) > 0) {
          targetVinegar = targetVinegar.subtract(rankerVinegar);
        } else {
          targetVinegar = BigInteger.ZERO;
          data.setSuccess(true);
        }

        event.setData(data);
        wsUtils.convertAndSendToUser(
            accountService.findById(ranker.getAccountId()).orElseThrow().getUuid(),
            LadderController.PRIVATE_EVENTS_DESTINATION, event);
        wsUtils.convertAndSendToUser(
            accountService.findById(target.getAccountId()).orElseThrow().getUuid(),
            LadderController.PRIVATE_EVENTS_DESTINATION, event);

        if (data.isSuccess()) {
          removeMulti(new Event<>(LadderEventType.REMOVE_MULTI, targetAccount.getId()), ladder);
        }

        ranker.setVinegar(BigInteger.ZERO);
        target.setVinegar(targetVinegar);
        return true;
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }

    return false;
  }

  /**
   * Removes 1 Multi from the active ranker of an account on a specific ladder. This mainly happens
   * after successfully thrown vinegar.
   *
   * @param event  the event that contains the information for the removal
   * @param ladder the ladder the ranker is on
   */
  void removeMulti(Event<LadderEventType> event, LadderEntity ladder) {
    try {
      AccountEntity account = accountService.findById(event.getAccountId()).orElseThrow();
      RankerEntity ranker = rankerService.findHighestActiveRankerOfAccount(account)
          .orElseThrow();

      if (!ladder.getId().equals(ranker.getLadderId())) {
        return;
      }

      ranker.setMultiplier(Math.max(1, ranker.getMultiplier() - 1));
      ranker.setBias(0);
      ranker.setPower(BigInteger.ZERO);
      ranker.setPoints(BigInteger.ZERO);
      wsUtils.convertAndSendToTopicWithNumber(LadderController.TOPIC_EVENTS_DESTINATION,
          ladder.getNumber(), event);
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
  }
}
