package info.vizierdb.spreadsheet

import org.apache.spark.sql.catalyst.expressions.SortOrder
import info.vizierdb.types._

case class ReferenceFrame(transformations: Seq[RowTransformation] = Seq.empty)
{
  def needsTranform = !transformations.isEmpty

  def forward(sourceRow: RowReference): Option[RowReference] = 
    transformations.foldLeft(Some(sourceRow):Option[RowReference]) { 
      case (Some(row), xform) => xform.forward(row) 
      case (None, _) => None
    }

  def forward(sourceRows: RangeSet): RangeSet = 
    transformations.foldLeft(sourceRows) { 
      (rows, xform) => xform.forward(rows)
    }

  def forward(row: Long): Option[Long] =
    transformations.foldLeft(Some(row):Option[Long]) {
      case (Some(row), xform) => xform.forward(row)
      case (None, _) => None
    }

  def backward(sourceRow: RowReference): RowReference = 
    transformations.foldRight(sourceRow) { _.backward(_) }

  def backward(sourceRows: RangeSet): RangeSet = 
    transformations.foldRight(sourceRows) { 
      (xform, rows) => xform.backward(rows)
    }

  def relativeTo(other: ReferenceFrame): ReferenceFrame = 
  {
    if(other.transformations.isEmpty){ return this }
    assert(
      transformations.take(other.transformations.size) == other.transformations,
      "relativeTo on a divergent history"
    )
    ReferenceFrame(transformations.drop(other.transformations.size))
  }

  def +(xform: RowTransformation): ReferenceFrame =
    ReferenceFrame(transformations :+ xform)
}