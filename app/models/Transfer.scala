package models

import java.sql.Timestamp

import org.squeryl.KeyedEntity
import play.api.libs.json.{JsValue, Json, Writes}
import utils.Formatter.timestampFormatFactory

class Transfer(
                val leagueUserId: Long,
                val pickeeId: Long,
                val isBuy: Boolean,
                val scheduledFor: Timestamp,
                var processed: Boolean,
                val cost: BigDecimal,
                val wasWildcard: Boolean = false
              ) extends KeyedEntity[Long] {
  val id: Long = 0
  lazy val pickee = AppDB.pickeeToTransfer.right(this).single
}

object Transfer{
  implicit val timestampFormat = timestampFormatFactory("yyyy-MM-dd HH:mm:ss")
  implicit val implicitWrites = new Writes[Transfer] {
    def writes(t: Transfer): JsValue = {
      Json.obj(
        "isBuy" -> t.isBuy,
        "scheduledFor" -> t.scheduledFor,
        "processed" -> t.processed,
        "cost" -> t.cost,
        "wasWildcard" -> t.wasWildcard,
        "pickeeId" -> t.pickee.externalId,
        "pickeeName" -> t.pickee.name
      )
    }
  }
}