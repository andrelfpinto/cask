// Load dependencies
import $ivy.{`org.pegdown:pegdown:1.6.0`, `com.lihaoyi::scalatags:0.6.5`}
import $file.pageStyles, pageStyles._
import $file.pages, pages._
import scalatags.Text.all._
import $file.^.ci.version
import ammonite.ops._
import collection.JavaConverters._
import org.pegdown.{PegDownProcessor, ToHtmlSerializer, LinkRenderer, Extensions}
import org.pegdown.ast.{VerbatimNode, ExpImageNode, HeaderNode, TextNode, SimpleNode, TableNode}

val (releaseTag, label) = version.publishVersion

val postsFolder = cwd/'pages

interp.watch(postsFolder)

val targetFolder = cwd/'target


val (markdownFiles, otherFiles) = ls! postsFolder partition (_.ext == "md")
markdownFiles.foreach(println)
// Walk the posts/ folder and parse out the name, full- and first-paragraph-
// HTML of each post to be used on their respective pages and on the index

val posts = {
  val split = for(path <- markdownFiles) yield {
    val Array(number, name) = path.last.split(" - ", 2)
    (number, name.stripSuffix(".md"), path)
  }
  for ((index, name, path) <- split.sortBy(_._1.toInt)) yield {
    val processor = new PegDownProcessor(
      Extensions.FENCED_CODE_BLOCKS | Extensions.TABLES | Extensions.AUTOLINKS
    )

    val txt =
      """\$\$\$([a-zA-Z_0-9]+)""".r.replaceAllIn(
        read(path),
        m => {
          val g = m.group(1)
          val txt = read(ls(pwd/up/'example/g/'app/'src).head).replace("$", "\\$")
          val downloadLink =
            s"https://github.com/lihaoyi/cask/releases/download/$releaseTag/$g-$releaseTag.zip"

          s"""
             |```scala
             |$txt
             |```
             |
             |- [example project]($downloadLink)""".stripMargin
        }
      )

    val ast = processor.parseMarkdown(txt.toArray)
    val headers = collection.mutable.Buffer.empty[(String, Int)]
    class Serializer extends ToHtmlSerializer(new LinkRenderer){
      override def printImageTag(rendering: LinkRenderer.Rendering) {
        printer.print("<div style=\"text-align: center\"><img")
        printAttribute("src", rendering.href)
        // shouldn't include the alt attribute if its empty
        if(!rendering.text.equals("")){
          printAttribute("alt", rendering.text)
        }
        import collection.JavaConversions._
        for (attr <- rendering.attributes) {
          printAttribute(attr.name, attr.value)
        }
        printer.print(" style=\"max-width: 100%; max-height: 500px\"")
        printer.print(" /></div>")
      }
      override def visit(node: HeaderNode) = {
        val tag = "h" + node.getLevel()


        val id =
          node
            .getChildren
            .asScala
            .collect{case t: TextNode => t.getText}
            .mkString

        headers.append(id -> node.getLevel())


        val setId = s"id=${'"'+sanitize(id)+'"'}"
        printer.print(s"""<$tag $setId class="${Styles.hoverBox.name}">""")
        visitChildren(node)
        printer.print(
          a(href := ("#" + sanitize(id)), Styles.hoverLink)(
            i(cls := "fa fa-link", aria.hidden := true)
          ).render
        )
        printer.print(s"</$tag>")
      }

      override def visit(node: VerbatimNode) = {
        printer.println().print(
          """<pre style="background-color: #f8f8f8">""" +
            s"""<code style="white-space:pre; background-color: #f8f8f8" class="${node.getType()}">"""
        )

        var text = node.getText()
        // print HTML breaks for all initial newlines
        while(text.charAt(0) == '\n') {
          printer.print("\n")
          text = text.substring(1)
        }
        printer.printEncoded(text)
        printer.print("</code></pre>")
      }
      override def visit(node: TableNode) = {
        currentTableNode = node
        printer.print("<table class=\"table table-bordered\">")
        visitChildren(node)
        printer.print("</table>")
        currentTableNode = null
      }
    }

    val postlude = Seq[Frag](
      hr,

      p(
        b("About the Author:"),
        i(
          " Haoyi is a software engineer, an early contributor to ",
          a(href:="http://www.scala-js.org/")("Scala.js"),
          ", and the author of many open-source Scala tools such as Cask, the ",
          a(href:="lihaoyi.com/Ammonite", "Ammonite REPL"), " and ",
          a(href:="https://github.com/lihaoyi/fastparse", "FastParse"), ". "
        )
      ),
      p(
        i(
          "If you've enjoy using Cask, or enjoyed using Haoyi's other open ",
          "source libraries, please chip in (or get your Company to chip in!) via ",
          a(href:="https://www.patreon.com/lihaoyi", "Patreon"), " so he can ", "continue his open-source work"
        )
      ),
      hr
    ).render

    val rawHtmlContent = new Serializer().toHtml(ast) + postlude
    PostInfo(name, headers, rawHtmlContent)
  }
}

def formatRssDate(date: java.time.LocalDate) = {
  date
    .atTime(0, 0)
    .atZone(java.time.ZoneId.of("UTC"))
    .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
}

@main
def main(publish: Boolean = false) = {

  rm! targetFolder

  mkdir! targetFolder/'page
  for(otherFile <- otherFiles){
    cp(otherFile, targetFolder/'page/(otherFile relativeTo postsFolder))
  }

  cp(pwd/"favicon.png", targetFolder/"favicon.ico")
  cp(pwd/"logo-white.svg", targetFolder/"logo-white.svg")

  for(i <- posts.indices){
    val post = posts(i)

    val adjacentLinks = div(display.flex, flexDirection.row, justifyContent.spaceBetween)(
      for((j, isNext) <- Seq(i-1 -> false, i+1 -> true))
        yield posts.lift(j) match{
          case None => div()
          case Some(dest) =>
            renderAdjacentLink(
              isNext,
              dest.name,
              (i == 0, j == 0) match {
                case (true, true) => s"index.html"
                case (true, false) => s"page/${sanitize(dest.name)}.html"
                case (false, true) => s"../index.html"
                case (false, false) => s"${sanitize(dest.name)}.html"
              }
            )
        }
    )


    write(
      if (i == 0) targetFolder / "index.html"
      else targetFolder/'page/s"${sanitize(post.name)}.html",
      postContent(
        i == 0,
        post,
        adjacentLinks,
        posts.map(_.name)
      )
    )
  }
}