package views.support

import common._
import conf.Switches.TagLinking
import model._

import java.net.URLEncoder._
import org.apache.commons.lang.StringEscapeUtils
import org.jboss.dna.common.text.Inflector
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.jsoup.Jsoup
import org.jsoup.nodes.{ Element, Document }
import org.jsoup.safety.{ Whitelist, Cleaner }
import play.api.libs.json.Json._
import play.api.libs.json.Writes
import play.api.mvc.RequestHeader
import play.api.mvc.SimpleResult
import play.api.templates.Html
import scala.collection.JavaConversions._
import conf.Switches.ShowAllArticleEmbedsSwitch

sealed trait Style {
  val className: String
  val showMore: Boolean
}

object Featured extends Style {
  val className = "featured"
  val showMore = false
}

/**
 * trails display trailText and thumbnail (if available)
 */
object Thumbnail extends Style {
  val className = "with-thumbnail"
  val showMore = false
}

/**
 * trails only display headline
 */
object Headline extends Style {
  val className = "headline-only"
  val showMore = false
}

/**
 * trails for the section fronts
 */
object SectionFront extends Style {
  val className = "section-front"
  val showMore = false
}

/**
 * New 'collection' templates
 */
sealed trait Container {
  val containerType: String
  val showMore: Boolean
  val tone: String
}

case class NewsContainer(showMore: Boolean = true) extends Container {
  val containerType = "news"
  val tone = "news"
}
case class SportContainer(showMore: Boolean = true) extends Container {
  val containerType = "sport"
  val tone = "news"
}
case class CommentContainer(showMore: Boolean = true) extends Container {
  val containerType = "comment"
  val tone = "comment"
}
case class CommentAndDebateContainer(showMore: Boolean = true) extends Container {
  val containerType = "commentanddebate"
  val tone = "comment"
}
case class FeaturesContainer(showMore: Boolean = true, adSlot: Option[AdSlot] = None) extends Container {
  val containerType = "features"
  val tone = "feature"
}
case class PopularContainer(showMore: Boolean = true) extends Container {
  val containerType = "popular"
  val tone = "news"
}
case class PeopleContainer(showMore: Boolean = true, adSlot: Option[AdSlot] = None) extends Container {
  val containerType = "people"
  val tone = "feature"
}
case class SpecialContainer(showMore: Boolean = true) extends Container {
  val containerType = "special"
  val tone = "news"
}
case class SectionContainer(showMore: Boolean = true, tone: String = "news") extends Container {
  val containerType = "section"
}

sealed trait AdSlot {
  val baseName: String
  val medianName: String
}
object AdSlot {

  object First extends AdSlot {
    val baseName = "x49"
    val medianName = "Middle1"
  }

  object Second extends AdSlot {
    val baseName = "Bottom2"
    val medianName = "Middle"
  }

}


object MetadataJson {

  def apply(data: (String, Any)): String = data match {
    // thank you erasure
    case (key, value) if value.isInstanceOf[Map[_, _]] =>
      val valueJson = value.asInstanceOf[Map[String, Any]].map(MetadataJson(_)).mkString(",")
      s""""$key": {$valueJson}"""
    case (key, value) if value.isInstanceOf[Seq[_]] =>
      val valueJson = value.asInstanceOf[Seq[(String, Any)]].map(v => s"{${MetadataJson(v)}}").mkString(",")
      s""""$key": [${valueJson}]""".format(key, valueJson)
    case (key, value) =>
      s""""${JavaScriptVariableName(key)}": ${JavaScriptValue(value)}"""
  }
}

object JSON {
  //we wrap the result in an Html so that play does not escape it as html
  //after we have gone to the trouble of escaping it as Javascript
  def apply[T](json: T)(implicit tjs: Writes[T]): Html = Html(stringify(toJson(json)))
}

//annoyingly content api will sometimes have things surrounded by <p> tags and sometimes not.
//since you cannot nest <p> tags this causes all sorts of problems
object RemoveOuterParaHtml {

  def apply(html: Html): Html = this(html.body)

  def apply(text: String): Html = {
    val fragment = Jsoup.parseBodyFragment(text).body()
    if (!fragment.html().startsWith("<p>")) {
      Html(text)
    } else {
      Html(fragment.html.drop(3).dropRight(4))
    }
  }
}

object JavaScriptValue {
  def apply(value: Any) = value match {
    case b: Boolean => b
    case s => s""""${s.toString.replace(""""""", """\"""")}""""
  }
}

object JavaScriptVariableName {
  def apply(s: String): String = {
    val parts = s.split("-").toList
    (parts.headOption.toList ::: parts.tail.map(firstLetterUppercase )).mkString
  }
  private def firstLetterUppercase(s: String) = s.head.toUpper + s.tail
}

case class RowInfo(rowNum: Int, isLast: Boolean = false) {
  lazy val isFirst = rowNum == 1
  lazy val isEven = rowNum % 2 == 0
  lazy val isOdd = !isEven
  lazy val rowClass = rowNum match {
    case 1 => s"first ${_rowClass}"
    case _ if isLast => s"last ${_rowClass}"
    case _ => _rowClass
  }
  private lazy val _rowClass = if (isEven) "even" else "odd"

  def indexIsInSameCarousel(carouselWidth: Int, candidate: Int): Boolean = {
    val tolerance = (carouselWidth - 1) / 2 // Your problem if carouselWidth % 2 == 0
    val carousel = (rowNum - tolerance) to (rowNum + tolerance)
    carousel contains candidate
  }
}

trait HtmlCleaner {
  def clean(d: Document): Document
}

object BlockNumberCleaner extends HtmlCleaner {

  private val Block = """<!-- Block (\d*) -->""".r

  override def clean(document: Document): Document = {
    document.getAllElements.foreach { element =>
      val blockComments = element.childNodes.flatMap { node =>
        node.toString.trim match {
          case Block(num) =>
            Option(node.nextSibling).foreach(_.attr("id", s"block-$num"))
            Some(node)
          case _ => None
        }
      }
      blockComments.foreach(_.remove())
    }
    document
  }
}

case class VideoEmbedCleaner(contentVideos: Seq[VideoElement]) extends HtmlCleaner {

  override def clean(document: Document): Document = {
    document.getElementsByClass("element-video").foreach { element: Element =>
      element.child(0).wrap("<div class=\"element-video__wrap\"></div>")
    }

    document.getElementsByClass("gu-video").foreach { element: Element =>
      val flashMediaElement = conf.Static.apply("flash/flashmediaelement.swf").path

      val mediaId = element.attr("data-media-id")
      val asset = findVideoFromId(mediaId)

      // add the poster url
      asset.flatMap(_.image).flatMap(Item620.bestFor).map(_.toString()).foreach{ url =>
        element.attr("poster", url)
      }

      asset.foreach( video => {
        element.append(
          s"""<object type="application/x-shockwave-flash" data="$flashMediaElement" width="620" height="350">
                <param name="allowFullScreen" value="true" />
                <param name="movie" value="$flashMediaElement" />
                <param name="flashvars" value="controls=true&amp;file=${video.url.getOrElse("")}" />
                Sorry, your browser is unable to play this video.
              </object>""")

        element.wrap("<div class=\"media-proportional-container\"></div>")
      })
    }
    document
  }

  def findVideoFromId(id:String): Option[VideoAsset] = {
    contentVideos.filter(_.id == id).flatMap(_.videoAssets).find(_.mimeType == Some("video/mp4"))
  }
}

case class PictureCleaner(contentImages: Seq[ImageElement]) extends HtmlCleaner with implicits.Numbers {

  def clean(body: Document): Document = {
    body.getElementsByTag("figure").foreach { fig =>
      if(!fig.hasClass("element-comment") && !fig.hasClass("element-witness")) {
        fig.attr("itemprop", "associatedMedia")
        fig.attr("itemscope", "")
        fig.attr("itemtype", "http://schema.org/ImageObject")
        val mediaId = fig.attr("data-media-id")
        val asset = findImageFromId(mediaId)

        fig.getElementsByTag("img").foreach { img =>
          fig.addClass("img")
          img.attr("itemprop", "contentURL")
          val src = img.attr("src")
          img.attr("src", ImgSrc(src, Naked).toString())

          asset.foreach { image =>
            fig.addClass(image.width match {
              case width if width <= 220 => "img--base img--inline"
              case width if width < 460 => "img--median"
              case width => "img--extended"
            })
            fig.addClass(image.height match {
              case height if height > image.width => "img--portrait"
              case height if height < image.width => "img--landscape"
              case height => ""
            })
          }
        }
        fig.getElementsByTag("figcaption").foreach { figcaption =>

          // content api/ tools sometimes pops a &nbsp; in the blank field
          if (!figcaption.hasText || figcaption.text().length < 2) {
            figcaption.remove()
          } else {
            figcaption.attr("itemprop", "description")
          }
        }
      }
    }
    body
  }

  def findImageFromId(id:String): Option[ImageAsset] = {
    contentImages.find(_.id == id).flatMap(_.largestImage)
  }
}

object BulletCleaner {
  def apply(body: String): String = body.replace("•", """<span class="bullet">•</span>""")
}

object UnindentBulletParents extends HtmlCleaner with implicits.JSoup {
  def clean(body: Document): Document = {
    val bullets = body.getElementsByClass("bullet")
    bullets flatMap { _.parentTag("p") } foreach { _.addClass("bullet-container") }
    body
  }
}

case class InBodyLinkCleaner(dataLinkName: String)(implicit val edition: Edition) extends HtmlCleaner {
  def clean(body: Document): Document = {
    val links = body.getElementsByTag("a")

    links.foreach { link =>
      link.attr("href", LinkTo(link.attr("href"), edition))
      link.attr("data-link-name", dataLinkName)
      link.addClass("u-underline")
    }
    body
  }
}

object TweetCleaner extends HtmlCleaner {

  override def clean(document: Document): Document = {
    document.getElementsByClass("twitter-tweet").foreach { element =>
      val el = element.clone()
      if (el.children.size > 1) {
        val body = el.child(0).attr("class", "tweet-body")
        val date = el.child(1).attr("class", "tweet-date")
        val user = el.ownText()
        val userEl = document.createElement("span").attr("class", "tweet-user").text(user)

        element.empty().attr("class", "tweet")
        element.appendChild(userEl).appendChild(date).appendChild(body)
      }
    }
    document
  }
}

class TagLinker(article: Article)(implicit val edition: Edition) extends HtmlCleaner{
  def clean(d: Document): Document = {
    if (TagLinking.isSwitchedOn && article.linkCounts.noLinks) {
      val paragraphs = d.getElementsByTag("p")

      // order by length of name so we do not make simple match errors
      // e.g 'Northern Ireland' & 'Ireland'
      article.keywords.filterNot(_.isSectionTag).sortBy(_.name.length).reverse.foreach{ keyword =>
        // don't link again in paragraphs we have already upgraded
        val unlinkedParas = paragraphs.filterNot(_.html.contains("<a"))
        unlinkedParas.find(_.text().contains(" " + keyword.name + " ")).foreach{ p =>

          val tagLink = d.createElement("a")
          tagLink.attr("href", LinkTo(keyword.url, edition))
          tagLink.text(keyword.name)
          tagLink.attr("data-link-name", "auto-linked-tag")
          tagLink.addClass("u-underline")

          p.html(p.html().replaceFirst(keyword.name, tagLink.toString))
        }
      }
    }
    d
  }
}

object InBodyElementCleaner extends HtmlCleaner {

  private val supportedElements = Set(
    "element-tweet",
    "element-video",
    "element-image",
    "element-witness",
    "element-comment",
    "element-interactive"
  )

  override def clean(document: Document): Document = {
    // this code REMOVES unsupported embeds
    if(ShowAllArticleEmbedsSwitch.isSwitchedOff) {
      val embeddedElements = document.getElementsByTag("figure").filter(_.hasClass("element"))
      val unsupportedElements = embeddedElements.filterNot(e => supportedElements.exists(e.hasClass))
      unsupportedElements.foreach(_.remove())
    }
    document
  }
}

case class Summary(amount: Int) extends HtmlCleaner {
  override def clean(document: Document): Document = {
    val children = document.body().children().toList
    val para: Option[Element] = children.filter(_.nodeName() == "p").take(amount).lastOption
    // if there is are no p's, just take the first n things (could be a blog)
    para match {
      case Some(p) => children.drop(children.indexOf(p)).foreach(_.remove())
      case _ => children.drop(amount).foreach(_.remove())
    }
    document
  }
}

// whitespace in the <span> below is significant
// (results in spaces after author names before commas)
// so don't add any, fool.
object ContributorLinks {
  def apply(text: String, tags: Seq[Tag]): Html = Html {
    tags.foldLeft(text) {
      case (t, tag) =>
        t.replaceFirst(tag.name,
          <span itemscope=" " itemtype="http://schema.org/Person" itemprop="author"><a rel="author" class="tone-colour" itemprop="url name" data-link-name="auto tag link" href={s"/${tag.id}"}>{tag.name}</a></span>.toString())
    }
  }
  def apply(html: Html, tags: Seq[Tag]): Html = apply(html.body, tags)
}

object OmnitureAnalyticsData {
  def apply(page: MetaData, jsSupport: String, path: String)(implicit request: RequestHeader): Html = {

    val data = page.metaData.map { case (key, value) => key -> value.toString }
    val pageCode = data.get("page-code").getOrElse("")
    val contentType = data.get("content-type").getOrElse("")
    val section = data.get("section").getOrElse("")
    val platform = "frontend"
    val publication = data.get("publication").getOrElse("")
    val omnitureEvent = data.get("omnitureEvent").getOrElse("")
    val registrationType = data.get("registrationType").getOrElse("")
    val omnitureErrorMessage = data.get("omnitureErrorMessage").getOrElse("")

    val isContent = page match {
      case c: Content => true
      case _ => false
    }

    val pageName = page.analyticsName
    val analyticsData = Map(
      ("g", path),
      ("ns", "guardian"),
      ("pageName", pageName),
      ("cdp", "2"),
      ("v7", pageName),
      ("c3", publication),
      ("ch", section),
      ("c9", section),
      ("c4", data.get("keywords").getOrElse("")),
      ("c6", data.get("author").getOrElse("")),
      ("c8", pageCode),
      ("v8", pageCode),
      ("c9", contentType),
      ("c10", data.get("tones").getOrElse("")),
      ("c11", section),
      ("c13", data.get("series").getOrElse("")),
      ("c25", data.get("blogs").getOrElse("")),
      ("c14", data("build-number")),
      ("c19", platform),
      ("v19", platform),
      ("v67", "nextgen-served"),
      ("c30", if (isContent) "content" else "non-content"),
      ("c56", jsSupport),
      ("event", omnitureEvent),
      ("v23", registrationType),
      ("e27", omnitureErrorMessage)
    )


    Html(analyticsData map { case (key, value) => s"$key=${encode(value, "UTF-8")}" } mkString "&")
  }
}

object `package` extends Formats {

  private object inflector extends Inflector

  def withJsoup(html: Html)(cleaners: HtmlCleaner*): Html = withJsoup(html.body) { cleaners: _* }

  def withJsoup(html: String)(cleaners: HtmlCleaner*): Html = {
    val cleanedHtml = cleaners.foldLeft(Jsoup.parseBodyFragment(html)) { case (html, cleaner) => cleaner.clean(html) }
    Html(cleanedHtml.body.html)
  }

  implicit class Tags2tagUtils(t: Tags) {
    def typeOrTone: Option[Tag] = t.types.find(_.id != "type/article").orElse(t.tones.headOption)
  }

  implicit class Tags2inflector(t: Tag) {
    lazy val singularName: String = inflector.singularize(t.name)
    lazy val pluralName: String = inflector.pluralize(t.name)
  }

  implicit class Seq2zipWithRowInfo[A](seq: Seq[A]) {
    def zipWithRowInfo = seq.zipWithIndex.map {
      case (item, index) => (item, RowInfo(index + 1, seq.length == index + 1))
    }
  }
}

object Format {
  def apply(date: DateTime, pattern: String)(implicit request: RequestHeader): String = {
    val timezone = Edition(request).timezone
    date.toString(DateTimeFormat.forPattern(pattern).withZone(timezone))
  }
}

object cleanTrailText {
  def apply(text: String)(implicit edition: Edition): Html = {
    withJsoup(RemoveOuterParaHtml(BulletCleaner(text)))(InBodyLinkCleaner("in trail text link"))
  }
}

object StripHtmlTags {
  def apply(html: String): String = Jsoup.clean(html, Whitelist.none())
}

object StripHtmlTagsAndUnescapeEntities{
  def apply(html: String) : String = {
    val doc = new Cleaner(Whitelist.none()).clean(Jsoup.parse(html))
    val stripped = doc.body.html
    val unescaped = StringEscapeUtils.unescapeHtml(stripped)
    unescaped.replace("\"","&#34;")   //double quotes will break HTML attributes
  }
}

object CricketMatch {
  def apply(trail: Trail): Option[String] = trail match {
    case c: Content => c.cricketMatch
    case _ => None
  }
}

object VisualTone {

  private val Comment = "comment"
  private val News = "news"
  private val Feature = "feature"

  private val commentMappings = Seq(
    "tone/comment",
    "tone/letters",
    "tone/obituaries",
    "tone/profiles",
    "tone/editorials",
    "tone/analysis"
  )

  private val featureMappings = Seq(
    "tone/features",
    "tone/recipes",
    "tone/interview",
    "tone/performances",
    "tone/extract",
    "tone/reviews",
    "tone/albumreview",
    "tone/livereview",
    "tone/childrens-user-reviews"
  )


  // tones are all considered to be 'News' it is the default so we do not list news tones explicitly
  def apply(tags: Tags) = if(isComment(tags.tones)) Comment else if(isFeature(tags.tones)) Feature else News

  private def isComment(tones: Seq[Tag]) = tones.exists(t => commentMappings.contains(t.id))
  private def isFeature(tones: Seq[Tag]) = tones.exists(t => featureMappings.contains(t.id))
}

object RenderOtherStatus {
  def gonePage(implicit request: RequestHeader) = model.Page(request.path, "news", "This page has been removed", "GFE:Gone")
  def apply(result: SimpleResult)(implicit request: RequestHeader) = result.header.status match {
    case 404 => NoCache(NotFound)
    case 410 if request.isJson => Cached(60)(JsonComponent(gonePage, "status" -> "GONE"))
    case 410 => Cached(60)(Gone(views.html.expired(gonePage)))
    case _ => result
  }
}

object RenderClasses {

  def apply(classes: Map[String, Boolean]): String = apply(classes.filter(_._2).keys.toSeq:_*)

  def apply(classes: String*): String = classes.filter(_.nonEmpty).sorted.mkString(" ")

}

object GetClasses {

  def forCollectionItem(trail: Trail): String = {
    val f: Seq[(Trail) => String] = Seq(
      (trail: Trail) => trail match {
        case _: Gallery => "collection__item--content-type-gallery"
        case _: Video   => "collection__item--content-type-video"
        case _          => ""
      }
    )
    val baseClasses: Seq[String] = Seq(
      "l-row__item",
      "collection__item",
      s"collection__item--volume-${trail.group.getOrElse("0")}"
    )
    val classes = f.foldLeft(baseClasses){case (cl, fun) => cl :+ fun(trail)}
    RenderClasses(classes:_*)
  }

  def forItem(trail: Trail, firstContainer: Boolean): String = {
    val baseClasses: Seq[String] = Seq(
      "item",
      s"tone-${VisualTone(trail)}"
    )
    val f: Seq[(Trail, Boolean) => String] = Seq(
      (trail: Trail, firstContainer: Boolean) => trail match {
        case _: Gallery => "item--gallery"
        case _: Video   => "item--video"
        case _          => ""
      },
      (trail: Trail, firstContainer: Boolean) => if (firstContainer) {"item--force-image-upgrade"} else {""},
      (trail: Trail, firstContainer: Boolean) => if (trail.isLive) {"item--live"} else {""},
      (trail: Trail, firstContainer: Boolean) => if (trail.trailPicture(5,3).isEmpty || trail.imageAdjust == Some("hide")){
        "item--has-no-image"
      }else{
        "item--has-image"
      },
      (trail: Trail, firstContainer: Boolean) => trail.imageAdjust.map{ adjustValue =>
        s"item--imageadjust-$adjustValue"
      }.getOrElse("")
    )
    val classes = f.foldLeft(baseClasses){case (cl, fun) => cl :+ fun(trail, firstContainer)}
    RenderClasses(classes:_*)
  }

  def forFromage(trail: Trail, volumeOverride: Int, imageAdjustOverride: String): String = {
    val baseClasses: Seq[String] = Seq(
      "fromage",
      s"tone-${VisualTone(trail)}",
      "tone-accent-border"
    )
    val f: Seq[(Trail, Int, String) => String] = Seq(
      (trail: Trail, volumeOverride: Int, imageAdjustOverride: String) =>
        if (trail.isLive) {"item--live"} else {""},
      (trail: Trail, volumeOverride: Int, imageAdjustOverride: String) =>
        if (trail.trailPicture(5,3).isEmpty || trail.imageAdjust == Some("hide") || imageAdjustOverride == "hide"){
          "fromage--has-no-image"
        }else{
          "fromage--has-image"
        },
      (trail: Trail, volumeOverride: Int, imageAdjustOverride: String) =>
        trail.imageAdjust.map{ adjustValue =>
          s"fromage--imageadjust-$adjustValue"
        }.getOrElse(""),
      (trail: Trail, volumeOverride: Int, imageAdjustOverride: String) =>
        if (volumeOverride != 0) {
          s"fromage--volume-${volumeOverride}"
        } else {
          s"fromage--volume-${trail.group.getOrElse("0")}"
        }
    )
    val classes = f.foldLeft(baseClasses){case (cl, fun) => cl :+ fun(trail, volumeOverride, imageAdjustOverride)}
    RenderClasses(classes:_*)
  }

}
