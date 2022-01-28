package info.vizierdb.ui.components

import rx._
import org.scalajs.dom
import scalatags.JsDom.all._
import info.vizierdb.ui.rxExtras.RxBufferView
import info.vizierdb.ui.rxExtras.implicits._

class TableOfContents(workflow: Workflow)
                     (implicit owner: Ctx.Owner)
{

  def ModuleSummary(module: Module): Frag =
    module.toc.map { toc => li(a(href := s"#${module.id_attr}", toc.title)) }
              .getOrElse { li(visibility := "hidden", s"${module.command.packageId}.${module.command.commandId}") }

  def TentativeSummary(module: TentativeModule): Frag =
    li( 
      `class` := "tentative",
      Rx { span(
      module.editor().map { ed => s"${ed.packageId}.${ed.command.id}" }
                     .getOrElse { "new command" }:String
      ) } 
    )


  val moduleNodes =
    RxBufferView(ul(), 
      workflow.moduleViewsWithEdits
              .rxMap { 
                  case Left(module) => ModuleSummary(module)
                  case Right(edit) => TentativeSummary(edit)
                }
    )

  val root:Frag = 
    div(
      id := "table_of_contents", 
      `class` := "contents",
      h3("Table of Contents"),
      moduleNodes.root
    )
}