package v1.transfer

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import utils.TryHelper.{tryOrResponse, tryOrResponseRollback}
import models._
import play.api.db._
import auth._
import play.api.Logger
import utils.IdParser
import v1.user.UserRepo
import v1.team.TeamRepo
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo

case class TransferFormInput(buy: List[Long], sell: List[Long], isCheck: Boolean, wildcard: Boolean,
                             applyStartPeriod: Option[Int], applyEndPeriod: Option[Int], overrideLimitChecks: Boolean)

case class TransferSuccess(updatedMoney: BigDecimal, remainingTransfers: Option[Int])


case class ManualDraftFormInput(teams: List[TeamFormInput])
case class TeamFormInput(userId: Long, pickees: List[String])

object TransferSuccess{
  implicit val implicitWrites = new Writes[TransferSuccess] {
    def writes(t: TransferSuccess): JsValue = {
      Json.obj(
        "updatedMoney" -> t.updatedMoney,
        "remainingTransfers" -> t.remainingTransfers
      )
    }
  }
}

case class RecycleCardsFormInput(cardIds: List[Long])

class TransferController @Inject()(
                                    cc: ControllerComponents, Auther: Auther, transferRepo: TransferRepo,
                                    userRepo: UserRepo, teamRepo: TeamRepo, pickeeRepo: PickeeRepo)
                                  (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers
  private val logger = Logger("application")
  private val transferForm: Form[TransferFormInput] = {

    Form(
    mapping(
    "buy" -> default(list(of(longFormat)), List()),
    "sell" -> default(list(of(longFormat)), List()),
    "isCheck" -> boolean,
    "wildcard" -> default(boolean, false),
      "applyStartPeriod" -> optional(number),
      "applyEndPeriod" -> optional(number),
    "overrideLimitChecks" -> default(boolean, false)
    )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }

  private val manualDraftForm: Form[ManualDraftFormInput] = {
    Form(
      mapping("teams" -> list(mapping(
        "userId" -> of(longFormat),
        "pickees" -> list(nonEmptyText)
      )(TeamFormInput.apply)(TeamFormInput.unapply)
      )
      )(ManualDraftFormInput.apply)(ManualDraftFormInput.unapply)
    )
  }

  // prob not really necessary to use form here. could just be raw array
  private val recycleCardsForm: Form[RecycleCardsFormInput] = {
    Form(mapping("cardIds" -> list(of(longFormat)))(RecycleCardsFormInput.apply)(RecycleCardsFormInput.unapply))
  }
  implicit val parser = parse.default

  // todo add a transfer check call
  def transferReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    makeTransfer(request.league, request.user)
  }

  def getUserTransfersReq(userId: String, leagueId: String) = (new LeagueAction(leagueId) andThen
    new UserAction(userRepo, db)(userId).apply()).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        Ok(Json.toJson(transferRepo.getUserTransfer(request.user.userId)))
      }
    }
  }

  def appendDraftQueueReq(userId: String, leagueId: String, pickeeIdStr: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        (for {
          pickeeId <- IdParser.parseIntId(Some(pickeeIdStr), "pickee", required=true)
          internalPickeeId = pickeeRepo.getInternalId(request.league.leagueId, pickeeId.get).get
          out = transferRepo.appendDraftQueue(request.user.userId, internalPickeeId)
        } yield Ok(Json.obj("success" -> true))).fold(identity, identity)
      }
    }
  }

  def deleteDraftQueueReq(userId: String, leagueId: String, pickeeIdStr: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        (for {
          pickeeId <- IdParser.parseIntId(Some(pickeeIdStr), "pickee", required=true)
          internalPickeeId = pickeeRepo.getInternalId(request.league.leagueId, pickeeId.get).get
          out = transferRepo.deleteDraftQueue(request.user.userId, internalPickeeId)
        } yield Ok(Json.obj("success" -> true))).fold(identity, identity)
      }
    }
  }

  def draftQueueAutopickReq(userId: String, leagueId: String, autopick: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        transferRepo.draftQueueAutopick(request.user.userId, autopick.toLowerCase == "on")
        Ok(Json.obj("success" -> true))
      }
    }
  }

  def draftReq(userId: String, leagueId: String, pickeeIdStr: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withTransaction { implicit c =>
        // TODO rollback!
        tryOrResponseRollback({
        (for {
          pickeeId <- IdParser.parseIntId(Some(pickeeIdStr), "pickee", required=true)
          internalPickeeId = pickeeRepo.getInternalId(request.league.leagueId, pickeeId.get)
          drafted <- transferRepo.draftPickee(request.user.userId, request.league.leagueId, internalPickeeId.get)
          out = Ok(Json.toJson(drafted))
        } yield out).fold(identity, identity)}, c, InternalServerError("Something went wrong")).fold(identity, identity)
      }
    }
  }

  def getDraftOrderReq(leagueId: String) = new LeagueAction(leagueId).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        Ok(Json.obj(
          "order" -> transferRepo.getDraftOrder(request.league.leagueId),
          "nextDraftDeadline" -> request.league.nextDraftDeadline
        ))
      }
    }
  }

  def getDraftOrderCountReq(leagueId: String) = new LeagueAction(leagueId).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        Ok(Json.toJson(transferRepo.getDraftOrderCount(request.league.leagueId)))
      }
    }
  }

  def getDraftQueueReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        val queue = transferRepo.getDraftQueue(request.league.leagueId, request.user.userId)
        val autopick = transferRepo.getAutopick(request.user.userId)
        val json = Json.obj("queue" -> queue, "autopick" -> JsBoolean(autopick))
        Ok(json)
      }
    }
  }

  def generateCardPackReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>
    Future {
      db.withTransaction { implicit c =>
        tryOrResponseRollback({transferRepo.buyCardPack(
          request.league.leagueId, request.user.userId, request.league.packSize.get, request.league.packCost.get
        ).fold(
          l => BadRequest(l), r => Ok(Json.toJson(r.toList))
        )}, c, InternalServerError("Something went wrong buying card")).fold(identity, identity)
      }
    }
  }

  def recycleCardsReq(userId: String, leagueId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async { implicit request =>

    def failure(badForm: Form[RecycleCardsFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }
    def success(input: RecycleCardsFormInput) = {
      Future {
        db.withTransaction { implicit c =>
          (for {
            _ <- if (teamRepo.cardsInTeam(input.cardIds, leagueRepo.getCurrentPeriod(request.league).map(_.value)))
              Left(BadRequest(Json.obj("success" -> false, "message" -> "Cannot recycle as currently in team"))) else Right(true)
            numRecycled <-
              tryOrResponseRollback(transferRepo.recycleCards(
                request.league.leagueId, request.user.userId, input.cardIds, request.league.recycleValue.get
              ), c,
                InternalServerError(Json.obj("success" -> false, "message" -> "Something went wrong recycling card")))
            // TODO can return user money rather than adhoc calc it
            out <- if (numRecycled > 0) Right(Ok(Json.toJson(TransferSuccess(
              request.user.money + request.league.recycleValue.get, None
            )))) else Left(BadRequest(Json.obj("success" -> false, "message" -> s"Card from: ${input.cardIds} does not exist or user: $userId does not own any of these cards")))
          } yield out).fold(identity, identity)
        }
      }
    }

    recycleCardsForm.bindFromRequest().fold(failure, success)
  }

  private def makeTransfer[A](league: LeagueRow, user: UserRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[TransferFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: TransferFormInput): Future[Result] = {
      val sell = input.sell.toSet
      val buy = input.buy.toSet
      if (sell.isEmpty && buy.isEmpty && !input.wildcard && !input.isCheck){
        return Future.successful(BadRequest("Attempted to confirm transfers, however no changes planned"))
      }

      Future {
        db.withTransaction { implicit c =>
          if (league.system == "card"){
            (for {
              // TODO does select for update lock/block other reads?
              _ <- validateDuplicates(input.sell, sell, input.buy, buy)
              // TODO what about last week of season
              currentPeriod = leagueRepo.getCurrentPeriod(league)
              userIsLateStart = currentPeriod.isDefined && user.entered.isAfter(currentPeriod.get.start)
              defaultPeriod <- if (userIsLateStart)
                currentPeriod.toRight(InternalServerError("Couldnt find current period")) else
                leagueRepo.getNextPeriod(league)
              periodStart = input.applyStartPeriod.getOrElse(defaultPeriod.value)
              periodEnd = input.applyEndPeriod
              _ <- validateNewUserCantChangeDuringPeriod(userIsLateStart, user.lateEntryLockTs, input.isCheck)
              userCards = pickeeRepo.getUserCards(league.leagueId, user.userId).toList
              currentTeamIds <- tryOrResponse(
                teamRepo.getUserTeamForPeriod(user.userId, periodStart, periodEnd).map(_.cardId).toSet
                , InternalServerError("Missing pickee externalPickeeId"))
              _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
              _ = println(s"sellOrWildcard: ${sell.mkString(",")}")
              _ <- validateIds(currentTeamIds, userCards.map(_.cardId).toSet, sell, buy)
              newTeamCardIds = (currentTeamIds -- sell) ++ buy
              newTeamPickeeIdsList = userCards.withFilter(c => newTeamCardIds.contains(c.cardId)).map(_.pickeeId)
              _ = println(s"newTeamCardIds: ${newTeamCardIds.mkString(",")}")
              newTeamPickeeIdsSet <- validateUniquePickees(newTeamPickeeIdsList)
              _ <- updatedTeamSize(newTeamPickeeIdsSet, league.teamSize, input.isCheck, league.forceFullTeams)
              _ <- if (input.overrideLimitChecks) Right(true) else transferRepo.validateLimits(newTeamPickeeIdsSet, league.leagueId)
              out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(user.money, None)))) else
                updateDBCardTransfer(
                  sell, buy, currentTeamIds, user, periodStart, periodEnd, userIsLateStart
                )
            } yield out).fold(identity, identity)
          }
          else if (league.system == "draft"){
            (for {
              // TODO add waiver_hold stuff
              _ <- validateDuplicates(input.sell, sell, input.buy, buy)
              leagueStarted = leagueRepo.isStarted(league)
              _ <- if (league.transferOpen) Right(true) else Left(BadRequest("Transfers not currently open for this league"))
              defaultPeriod <- leagueRepo.getNextPeriod(league)
              periodStart = input.applyStartPeriod.getOrElse(defaultPeriod.value)
              periodEnd = input.applyEndPeriod
              newRemaining <- updatedRemainingTransfers(leagueStarted, user.remainingTransfers, sell)
              pickees = pickeeRepo.getAllPickees(league.leagueId, active=Some(true)).toList
              currentTeamIds <- tryOrResponse(teamRepo.getUserTeam(user.userId).map(_.externalPickeeId).toSet
                , InternalServerError("Missing pickee externalPickeeId"))
              _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
              // use empty set as otherwis you cant rebuy heroes whilst applying wildcard
              takenPickees = pickeeRepo.takenPickeeIds(league.leagueId, periodStart)
              _ <- if (buy.intersect(takenPickees).nonEmpty)
                Left(BadRequest("Cannot buy pickee that is owned by someone else or has waiver cooldown")) else Right(true)
              _ <- validateIds(currentTeamIds, pickees.map(_.externalPickeeId).toSet, sell, buy)
              newTeamIds = (currentTeamIds -- sell) ++ buy
              _ = println(s"newTeamIds: ${newTeamIds.mkString(",")}")
              _ <- updatedTeamSize(newTeamIds, league.teamSize, input.isCheck, league.forceFullTeams)
              _ <- if (input.overrideLimitChecks) Right(true) else transferRepo.validateLimits(newTeamIds, league.leagueId)
              out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(BigDecimal(0), newRemaining)))) else
                updateDBTransfer(
                  league.leagueId, sell, buy, pickees, user,
                  leagueRepo.getCurrentPeriod(league).map(_.value).getOrElse(0), BigDecimal(0),
                  newRemaining, false, periodStart, periodEnd, league.system)
            } yield out).fold(identity, identity)
          }
          else{
          (for {
            // TODO does select for update lock/block other reads?
            _ <- validateDuplicates(input.sell, sell, input.buy, buy)
            leagueStarted = leagueRepo.isStarted(league)
            _ <- if (league.transferOpen) Right(true) else Left(BadRequest("Transfers not currently open for this league"))
            currentPeriod = leagueRepo.getCurrentPeriod(league)
            userIsLateStart = currentPeriod.isDefined && user.entered.isAfter(currentPeriod.get.start)
            defaultPeriod <- if (userIsLateStart)
              currentPeriod.toRight(InternalServerError("Couldnt find current period")) else
              leagueRepo.getNextPeriod(league)
            periodStart = input.applyStartPeriod.getOrElse(defaultPeriod.value)
            periodEnd = input.applyEndPeriod
            _ <- validateNewUserCantChangeDuringPeriod(userIsLateStart, user.lateEntryLockTs, input.isCheck)
            applyWildcard <- shouldApplyWildcard(input.wildcard, league.transferWildcard.get, user.usedWildcard, sell)
            newRemaining <- updatedRemainingTransfers(leagueStarted, user.remainingTransfers, sell)
            pickees = pickeeRepo.getAllPickees(league.leagueId).toList
            newMoney <- updatedMoney(user.money, pickees, sell, buy, applyWildcard, league.startingMoney)
            currentTeamIds <- tryOrResponse(teamRepo.getUserTeam(user.userId).map(_.externalPickeeId).toSet
            , InternalServerError("Missing pickee externalPickeeId"))
            _ = println(s"currentTeamIds: ${currentTeamIds.mkString(",")}")
            sellOrWildcard = if (applyWildcard) currentTeamIds else sell
            _ = println(s"sellOrWildcard: ${sellOrWildcard.mkString(",")}")
            // use empty set as otherwis you cant rebuy heroes whilst applying wildcard
            _ <- validateIds(if (applyWildcard) Set() else currentTeamIds, pickees.map(_.externalPickeeId).toSet, sell, buy)
            newTeamIds = (currentTeamIds -- sellOrWildcard) ++ buy
            _ = println(s"newTeamIds: ${newTeamIds.mkString(",")}")
            _ <- updatedTeamSize(newTeamIds, league.teamSize, input.isCheck, league.forceFullTeams)
            _ <- if (input.overrideLimitChecks) Right(true) else transferRepo.validateLimits(newTeamIds, league.leagueId)
            out <- if (input.isCheck) Right(Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))) else
              updateDBTransfer(
                league.leagueId, sellOrWildcard, buy, pickees, user,
                leagueRepo.getCurrentPeriod(league).map(_.value).getOrElse(0), newMoney,
                newRemaining, applyWildcard, periodStart, periodEnd, league.system)
          } yield out).fold(identity, identity) }
        }
      }
    }

    transferForm.bindFromRequest().fold(failure, success)
  }

  private def validateDuplicates(sellList: List[Long], sellSet: Set[Long], buyList: List[Long], buySet: Set[Long]): Either[Result, Any] = {
    if (buyList.size != buySet.size) return Left(BadRequest("Cannot buy twice"))
    if (sellList.size != sellSet.size) return Left(BadRequest("Cannot sell twice"))
    Right(true)
  }

  private def updatedRemainingTransfers(leagueStarted: Boolean, remainingTransfers: Option[Int], toSell: Set[Long]): Either[Result, Option[Int]] = {
    if (!leagueStarted){
      return Right(remainingTransfers)
    }
    val newRemaining = remainingTransfers.map(_ - toSell.size)
    newRemaining match{
      case Some(x) if x < 0 => Left(BadRequest(
        f"Insufficient remaining transfers: $remainingTransfers"
      ))
      case Some(x) => Right(Some(x))
      case None => Right(None)
    }
  }

  private def validateIds(
                                 currentTeamIds: Set[Long], availableIds: Set[Long], toSell: Set[Long],
                                 toBuy: Set[Long]): Either[Result, Boolean] = {
    // TODO return what ids are invalid
    logger.info("ValidateIds")
    logger.info(s"""availableIds: ${availableIds.mkString(",")}""")
    logger.info(s"""currentTeamIds: ${currentTeamIds.mkString(",")}""")
    logger.info(s"""toSell: ${toSell.mkString(",")}""")
    logger.info(s"""toBuy: ${toBuy.mkString(",")}""")
    (toSell ++ toBuy).subsetOf(availableIds) match {
      case true => {
        toBuy.intersect(currentTeamIds).isEmpty match {
          case true => {
            toSell.subsetOf(currentTeamIds) match {
              case true => Right(true)
              case false => Left(BadRequest("Cannot sell hero not in team"))
            }
          }
          case false => Left(BadRequest("Cannot buy hero already in team"))
        }

      }   case false => Left(BadRequest("Invalid pickee id used"))
    }
  }

  private def updatedMoney(
                            money: BigDecimal, pickees: Iterable[PickeeRow], toSell: Set[Long], toBuy: Set[Long],
                            wildcardApplied: Boolean, startingMoney: BigDecimal): Either[Result, BigDecimal] = {
    val spent = pickees.filter(p => toBuy.contains(p.externalPickeeId)).map(_.price).sum
    println(spent)
    println(toBuy)
    val updated = wildcardApplied match {
      case false => money + pickees.filter(p => toSell.contains(p.externalPickeeId)).map(_.price).sum - spent
      case true => startingMoney - spent
    }
    updated match {
      case x if x >= 0 => Right(x)
      case x => Left(BadRequest(
        f"Insufficient credits. Transfers would leave user at $x credits"
      ))
    }
  }

  private def updatedTeamSize(newTeamIds: Set[Long], leagueTeamSize: Int, isCheck: Boolean, forceFullTeams: Boolean): Either[Result, Int] = {
    newTeamIds.size match {
      case x if x <= leagueTeamSize => Right(x)
      case x if x < leagueTeamSize && !isCheck && forceFullTeams => Left(BadRequest(f"Cannot confirm transfers as team unfilled (require $leagueTeamSize)"))
      case x => Left(BadRequest(
        f"Exceeds maximum team size of $leagueTeamSize"
      ))
    }
  }

  private def validateUniquePickees(newTeamIds: List[Long]): Either [Result, Set[Long]] = {
    val setIds = newTeamIds.toSet
    if (newTeamIds.size != setIds.size) Left(BadRequest("Cannot have two identical players in team"))
    else Right(setIds)
  }

  private def validateNewUserCantChangeDuringPeriod(
                                                     userIsLateStart: Boolean, lateEntryLockTs: Option[LocalDateTime],
                                                     isCheck: Boolean
                                                   ): Either[Result, Any] = {
    // TODO but new user should be able to pick team for next period
    if (userIsLateStart && lateEntryLockTs.isDefined) Left(BadRequest("Have already locked team for this period"))
    else Right(true)
  }

  private def updateDBTransfer(
                                leagueId: Long, toSell: Set[Long], toBuy: Set[Long], pickees: Iterable[PickeeRow], user: UserRow,
                                period: Int, newMoney: BigDecimal, newRemaining: Option[Int],
                                applyWildcard: Boolean, periodStart: Int, periodEnd: Option[Int], system: String
                              )(implicit c: Connection): Either[Result, Result] = {
    tryOrResponseRollback({
      val currentTime = LocalDateTime.now()
      val toSellPickees = toSell.map(ts => pickees.find(_.externalPickeeId == ts).get)
      toSellPickees.map(
        p => transferRepo.insert(
          user.userId, p.internalPickeeId, false, currentTime, periodStart, periodEnd, p.price, applyWildcard
        )
      )
      val toBuyPickees = toBuy.map(tb => pickees.find(_.externalPickeeId == tb).get)
      toBuyPickees.map(
        p => transferRepo.insert(
          user.userId, p.internalPickeeId, true, currentTime, periodStart, periodEnd, p.price, applyWildcard
        ))
      val ct = teamRepo.getUserTeam(user.userId)
      val currentTeam = ct.map(_.cardId).toSet
      val toBuyCardIds = toBuyPickees.map(b => transferRepo.generateCard(leagueId, user.userId, b.internalPickeeId, "").cardId)
      val toSellCardIds = toSell.map(ts => ct.find(c => ts == c.externalPickeeId).get).map(_.cardId)
      transferRepo.changeTeam(
        user.userId, toBuyCardIds, toSellCardIds, currentTeam, periodStart, periodEnd
      )
      userRepo.updateFromTransfer(
        user.userId, newMoney, newRemaining, applyWildcard
      )
      if (system == "draft") transferRepo.releaseDraftPickees(leagueId, toSell, periodStart)
      Ok(Json.toJson(TransferSuccess(newMoney, newRemaining)))
    }, c, InternalServerError("Unexpected error whilst processing transfer")
    )
  }

  private def updateDBCardTransfer(
                                        toSell: Set[Long], toBuy: Set[Long], currentTeamIds: Set[Long], user: UserRow,
                                        periodStart: Int, periodEnd: Option[Int], userIsLateStart: Boolean
                                      )(implicit c: Connection): Either[Result, Result] = {
    tryOrResponseRollback({
        transferRepo.changeTeam(
          user.userId, toBuy, toSell, currentTeamIds, periodStart, periodEnd
        )
      if (userIsLateStart) userRepo.setlateEntryLockTs(user.userId)
      val newMoney = 10.0 // TODO actual new credits
      Ok(Json.toJson(TransferSuccess(newMoney, None)))
    }, c, InternalServerError("Unexpected error whilst processing transfer")
    )
  }

  private def shouldApplyWildcard(attemptingWildcard: Boolean, leagueHasWildcard: Boolean, usedWildcard: Boolean, toSell: Set[Long]): Either[Result, Boolean] = {
    if (toSell.nonEmpty && attemptingWildcard) return Left(BadRequest("Cannot sell heroes AND use wildcard at same time"))
    if (!attemptingWildcard) return Right(false)
    leagueHasWildcard match {
      case true => usedWildcard match {
        case true => Left(BadRequest("User already used up wildcard"))
        case _ => Right(true)
      }
      case _ => Left(BadRequest(f"League does not have wildcards"))
    }
  }

  def reportManualDraftReq(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId)
    andThen Auther.PermissionCheckAction).async { implicit request =>
    db.withConnection { implicit c => processJsonManualDraft(request.league)}
  }

  private def processJsonManualDraft[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[ManualDraftFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: ManualDraftFormInput) = Future(
      db.withConnection { implicit c =>
        transferRepo.manualDraft(league.leagueId, input).fold(identity, x => Ok(Json.toJson(x)))
      }
    )

    manualDraftForm.bindFromRequest().fold(failure, success)
  }

//  def pauseDraftReq(leagueId: String) = (new AuthAction() andThen
//    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async { implicit request =>
//    Future {
//        val pause = request.getQueryString("pause")
//        val unpause = request.getQueryString("unpause")
//        (pause, unpause) match{
//          case (None, None) => BadRequest("Must specify either pause/unpause as query string parameter")
//          case (Some(_), None) => db.withConnection { implicit c =>
//            transferRepo.setDraftPaused(request.league.leagueId, true)
//            Ok("success")
//          }
//          case (None, Some(_)) => db.withConnection { implicit c =>
//            transferRepo.setDraftPaused(request.league.leagueId, false)
//            Ok("success")
//          }
//          case (Some(_), Some(_)) => BadRequest("You've gone and specified to both pause and unpause you lemon")
//        }
//    }
//  }
}
