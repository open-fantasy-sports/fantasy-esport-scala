package auth

import play.api.mvc._
import play.api.mvc.Result
import play.api.mvc.Results._
import scala.concurrent.{ExecutionContext, Future}
import models.{League, AppDB}
import javax.inject.Inject
import utils.IdParser
import entry.SquerylEntrypointForMyApp._
import com.typesafe.config.{Config, ConfigFactory}

class AuthRequest[A](val apiKey: Option[String], request: Request[A]) extends WrappedRequest[A](request)

class AuthAction @Inject()(val parser: BodyParsers.Default)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[AuthRequest, AnyContent] with ActionTransformer[Request, AuthRequest] {
  def transform[A](request: Request[A]) = Future.successful {
    new AuthRequest(request.getQueryString("apiKey"), request)
  }
}

class LeagueRequest[A](val league: League, request: AuthRequest[A]) extends WrappedRequest[A](request) {
  def apiKey = request.apiKey
}

class Auther {
  val adminKey = ConfigFactory.load().getString("adminKey")
  def LeagueAction(leagueId: String)(implicit ec: ExecutionContext) = new ActionRefiner[AuthRequest, LeagueRequest] {
    def executionContext = ec
    def refine[A](input: AuthRequest[A]) = Future.successful {
      inTransaction(
        (for {
          leagueId <- IdParser.parseLongId(leagueId, "league")
          //league <- leagueRepo.get(leagueId).toRight(NotFound(f"League id $leagueId does not exist"))
          league <- AppDB.leagueTable.lookup(leagueId).toRight(NotFound(f"League id $leagueId does not exist"))
          out <- Right(new LeagueRequest(league, input))
        } yield out)
      )
    }
  }

  def PermissionCheckAction(implicit ec: ExecutionContext) = new ActionFilter[LeagueRequest] {
    def executionContext = ec
    def filter[A](input: LeagueRequest[A]) = Future.successful {
      if (input.league.apiKey != input.apiKey.getOrElse(""))
        Some(Forbidden(f"Must specify correct API key associated with this league, for this request. i.e. v1/leagues/1/startDay?apiKey=AASSSDDD"))
      else
        None
    }
  }

  def AdminCheckAction(implicit ec: ExecutionContext) = new ActionFilter[AuthRequest] {
    def executionContext = ec
    def filter[A](input: AuthRequest[A]) = Future.successful {
      if (adminKey != input.apiKey.getOrElse(""))
        Some(Forbidden(f"Only admin can perform this operation"))
      else
        None
    }
  }
}

