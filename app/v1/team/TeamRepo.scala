package v1.team

import java.sql.Connection
import java.time.LocalDateTime
import akka.actor.ActorSystem
import models._
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._

import javax.inject.{Inject, Singleton}

case class TeamOut(externalUserId: Long, username: String, leagueUserId: Long, start: Option[LocalDateTime],
                   end: Option[LocalDateTime], isActive: Boolean, pickees: Iterable[PickeeRow])

object TeamOut {
  implicit val implicitWrites = new Writes[TeamOut] {
    def writes(x: TeamOut): JsValue = {
      Json.obj(
        "userId" -> x.externalUserId,
        "username" -> x.username,
        "leagueUserId" -> x.leagueUserId,
        "start" -> x.start,
        "end" -> x.end,
        "isActive" -> x.isActive,
        "team" -> x.pickees
      )
    }
  }
}

class TeamExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

trait TeamRepo{
  def getLeagueUserTeam(leagueUserId: Long)(implicit c: Connection): Iterable[PickeeRow]
  def getAllLeagueUserTeam(leagueId: Long)(implicit c: Connection): Iterable[TeamOut]
}

@Singleton
class TeamRepoImpl @Inject()()(implicit ec: TeamExecutionContext) extends TeamRepo{
  override def getLeagueUserTeam(leagueUserId: Long)(implicit c: Connection): Iterable[PickeeRow] = {
    val q =
      """select p.pickee_id as internal_pickee_id, p.external_pickee_id, p.pickee_name, p.price from team t join pickee p using(pickee_id)
    where t.league_user_id = {leagueUserId} and upper(t.timespan) is NULL;
    """
    println(q)
    val out = SQL(q).on("leagueUserId" -> leagueUserId).as(PickeeRow.parser.*)
    println(out.mkString(","))
    out
  }

  override def getAllLeagueUserTeam(leagueId: Long)(implicit c: Connection): Iterable[TeamOut] = {
    val q =
      """select u.external_user_id, u.username, league_user_id, lower(t.timespan) as start, upper(t.timespan) as "end", true, p.pickee_id as internal_pickee_id, p.external_pickee_id,
        | p.pickee_name, p.price as pickee_price from team t
 |                   join pickee p using(pickee_id)
 |                   join league_user lu using(league_user_id)
 |                   join useru u using(user_id)
    where lu.league_id = {leagueId} and upper(t.timespan) is NULL;
    """
    println(q)
    val out = SQL(q).on("leagueId" -> leagueId).as(TeamRow.parser.*)
    println(out.mkString(","))
    teamRowsToOut(out)
  }

  private def teamRowsToOut(teamRows: Iterable[TeamRow]): Iterable[TeamOut] = {
    teamRows.groupBy(_.leagueUserId).map({case (leagueUserId, v) =>
      TeamOut(v.head.externalUserId, v.head.username, leagueUserId, v.head.start,
        v.head.end, v.head.isActive, v.map(p => PickeeRow(p.internalPickeeId, p.externalPickeeId, p.pickeeName, p.pickeePrice)))
    })
  }

}

