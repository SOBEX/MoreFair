package de.kaliburg.morefair.api;

import de.kaliburg.morefair.account.AccountService;
import de.kaliburg.morefair.account.entity.AccountEntity;
import de.kaliburg.morefair.account.type.AccountAccessRole;
import de.kaliburg.morefair.api.utils.RequestThrottler;
import de.kaliburg.morefair.api.utils.WsUtils;
import de.kaliburg.morefair.api.websockets.UserPrincipal;
import de.kaliburg.morefair.api.websockets.messages.WSMessage;
import de.kaliburg.morefair.events.Event;
import de.kaliburg.morefair.events.EventType;
import de.kaliburg.morefair.game.ranker.RankerService;
import java.util.UUID;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@Log4j2
public class AccountController {

  private static final String LOGIN_DESTINATION = "/queue/account/login";

  private final AccountService accountService;
  private final RankerService rankerService;
  private final RequestThrottler requestThrottler;
  private final WsUtils wsUtils;

  public AccountController(AccountService accountService, RankerService rankerService,
      RequestThrottler requestThrottler, WsUtils wsUtils) {
    this.accountService = accountService;
    this.rankerService = rankerService;
    this.requestThrottler = requestThrottler;
    this.wsUtils = wsUtils;
  }

  @MessageMapping("/account/login")
  public void login(SimpMessageHeaderAccessor sha, WSMessage wsMessage) throws Exception {
    try {
      UserPrincipal principal = wsUtils.convertMessageHeaderAccessorToUserPrincipal(sha);
      String uuid = StringEscapeUtils.escapeJava(wsMessage.getUuid());
      if (uuid == null || uuid.isBlank()) {
        if (requestThrottler.canCreateAccount(principal)) {
          wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION,
              accountService.createNewAccount(principal),
              HttpStatus.CREATED);
        } else {
          wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION, HttpStatus.FORBIDDEN);
        }
        return;
      }
      AccountEntity account = accountService.findAccountByUUID(UUID.fromString(uuid));
      if (account == null) {
        if (requestThrottler.canCreateAccount(principal)) {
          wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION,
              accountService.createNewAccount(principal),
              HttpStatus.CREATED);
        } else {
          wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION, HttpStatus.FORBIDDEN);
        }
        return;
      } else {
        if (account.getAccessRole().equals(AccountAccessRole.BANNED_PLAYER)) {
          wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION, HttpStatus.FORBIDDEN);
        }
        accountService.login(account, principal);
        wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION, account.convertToDTO());
      }

    } catch (IllegalArgumentException e) {
      wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION, HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      wsUtils.convertAndSendToUser(sha, LOGIN_DESTINATION, HttpStatus.INTERNAL_SERVER_ERROR);
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

  @MessageMapping("/account/name")
  public void changeUsername(SimpMessageHeaderAccessor sha, WSMessage wsMessage)
      throws Exception {
    try {
      String uuid = StringEscapeUtils.escapeJava(wsMessage.getUuid());
      String username = wsMessage.getContent();
      username = username.trim();
      if (username.length() > 32) {
        username = username.substring(0, 32);
      }
      username = StringEscapeUtils.escapeJava(username);

      log.debug("/app/account/name {} {}", uuid, username);

      AccountEntity account = accountService.findAccountByUUID(UUID.fromString(uuid));
      if (account == null || account.getAccessRole()
          .equals(AccountAccessRole.MUTED_PLAYER) || account.getAccessRole()
          .equals(AccountAccessRole.BANNED_PLAYER)) {
        return;
      }
      Event event = new Event(EventType.NAME_CHANGE, account.getId());
      event.setData(username);
      rankerService.addGlobalEvent(event);
    } catch (Exception e) {
      log.error(e.getMessage());
      e.printStackTrace();
    }
  }

}