package services

import model.{Trail, Collection}
import common.editions.Uk
import scala.concurrent.Future
import common.ExecutionContexts
import play.api.libs.json._
import model.Config

trait FrontPress extends ExecutionContexts {

  def generateJson(id: String): Future[JsObject] = {
    pressPage(id)
      .map(_.map{case (config, collection) =>
        Json.obj(
          config.id -> generateCollectionJson(config, collection)
        )})
      .map(_.foldLeft(Json.arr()){case (l, jsObject) => l :+ jsObject})
      .map( c =>
        Json.obj(
          ("id", id),
          ("collections", c)
        )
      )
  }

  def pressPage(id: String): Future[Iterable[(Config, Collection)]] = {
    val collectionIds: List[Config] = PorterConfigAgent.getConfigForId(id).getOrElse(Nil)
    val collections = collectionIds.map(config => PorterCollectionParser.getCollection(config.id, config, Uk, isWarmedUp=true).map((config, _)))
    Future.sequence(collections)
  }

  private def generateCollectionJson(config: Config, collection: Collection): JsValue = {
    Json.obj(
      ("apiQuery", config.contentApiQuery),
      ("displayName", config.displayName),
      ("tone", config.collectionTone),
      ("curated", collection.curated.map(generateTrailJson)),
      ("editorsPicks", collection.editorsPicks.map(generateTrailJson)),
      ("results", collection.results.map(generateTrailJson))
      //TODO: lastModified, modifiedBy
    )
  }

  private def generateTrailJson(trail: Trail): JsValue = {
    Json.obj(
      ("webTitle", trail.headline),
      ("webPublicationDate", trail.webPublicationDate),
      ("sectionName", trail.sectionName),
      ("sectionId", trail.section),
      ("id", trail.url),
      ("webUrl", trail.webUrl),
      ("meta", Json.obj()),
      ("trailText", trail.trailText),
      ("linkText", trail.linkText)
    )
  }
}

object FrontPress extends FrontPress