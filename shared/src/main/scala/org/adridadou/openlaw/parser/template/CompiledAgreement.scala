package org.adridadou.openlaw.parser.template

import cats.implicits._
import org.adridadou.openlaw.parser.template.expressions.Expression
import org.adridadou.openlaw.parser.template.formatters.Formatter
import org.adridadou.openlaw.{OpenlawBigDecimal, OpenlawBoolean, OpenlawValue}
import org.adridadou.openlaw.parser.template.variableTypes._
import org.adridadou.openlaw.result.{Failure, Result, Success}

import scala.annotation.tailrec
import scala.collection.mutable

final case class CompiledAgreement(
    header: TemplateHeader = TemplateHeader(Map.empty),
    block: Block = Block(),
    redefinition: VariableRedefinition = VariableRedefinition()
) extends CompiledTemplate {

  override def append(template: CompiledTemplate): CompiledTemplate =
    template match {
      case agreement: CompiledAgreement =>
        this.copy(
          header = TemplateHeader(this.header.values ++ agreement.header.values),
          block = Block(this.block.elems ++ agreement.block.elems),
          redefinition = VariableRedefinition(
            typeMap = this.redefinition.typeMap ++ agreement.redefinition.typeMap,
            descriptions = this.redefinition.descriptions ++ agreement.redefinition.descriptions
          )
        )
    }

  private val endOfParagraph = "(.)*[ |\t\r]*\n[ |\t\r]*\n[ |\t\r\n]*".r

  def structuredMainTemplate(
      executionResult: OpenlawExecutionState,
      overriddenFormatter: (
          Option[FormatterDefinition],
          TemplateExecutionResult
      ) => Option[Formatter]
  ): Result[StructuredAgreement] =
    structured(executionResult, None, mainTemplate = true, overriddenFormatter)

  def structuredInternal(
      executionResult: OpenlawExecutionState,
      path: Option[TemplatePath],
      overriddenFormatter: (
          Option[FormatterDefinition],
          TemplateExecutionResult
      ) => Option[Formatter]
  ): Result[StructuredAgreement] =
    structured(executionResult, path, mainTemplate = false, overriddenFormatter)

  private def structured(
      executionResult: OpenlawExecutionState,
      path: Option[TemplatePath],
      mainTemplate: Boolean,
      overriddenFormatter: (
          Option[FormatterDefinition],
          TemplateExecutionResult
      ) => Option[Formatter]
  ): Result[StructuredAgreement] =
    getAgreementElements(
      mutable.Buffer(),
      block.elems,
      executionResult,
      overriddenFormatter
    ).map { elements =>
      val paragraphs = cleanupParagraphs(generateParagraphs(elements))
      StructuredAgreement(
        executionResultId = executionResult.id,
        header = header,
        templateDefinition = executionResult.templateDefinition,
        path = path,
        mainTemplate = mainTemplate,
        paragraphs = paragraphs
      )
    }

  private def cleanupParagraphs(paragraphs: List[Paragraph]): List[Paragraph] =
    paragraphs
      .map(paragraph =>
        Paragraph(paragraph.elements.filter {
          case FreeText(elem) => !TextElement.isEmpty(elem)
          case _              => true
        })
      )
      .filter(paragraph => paragraph.elements.nonEmpty)

  private def generateParagraphs(
      elements: Seq[AgreementElement]
  ): List[Paragraph] =
    elements
      .foldLeft(ParagraphBuilder())({
        case (paragraphBuilder, element) =>
          element match {
            case txt: FreeText =>
              handleText(paragraphBuilder, txt)
            case sectionElement: SectionElement =>
              paragraphBuilder
                .closeLastParagraph()
                .add(sectionElement)
            case _ =>
              paragraphBuilder.add(element)
          }
      })
      .build

  def handleText(
      paragraphBuilder: ParagraphBuilder,
      ftxt: FreeText
  ): ParagraphBuilder =
    getParagraphs(ftxt.elem).foldLeft(paragraphBuilder)((builder, element) =>
      element match {
        case ParagraphSeparator =>
          builder.closeLastParagraph()
        case _ =>
          builder.add(FreeText(element))
      }
    )

  private def getParagraphs(te: TextElement): List[TextElement] =
    te match {
      case Text(text) =>
        val matches = endOfParagraph.findAllMatchIn(text).map(_.end)
        val (lastValue, newParagraphs) =
          matches.foldLeft((0, List.empty[TextElement])) {
            case ((lastIndex, p), endIndex) =>
              (
                endIndex,
                p :+ Text(text.substring(lastIndex, endIndex - 2)) :+ ParagraphSeparator
              )
          }
        newParagraphs :+ Text(text.substring(lastValue, text.length))
      case SectionBreak =>
        List(ParagraphSeparator, SectionBreak, ParagraphSeparator)
      case other => List(other)
    }

  @tailrec private def getAgreementElements(
      renderedElements: mutable.Buffer[AgreementElement],
      elements: List[TemplatePart],
      executionResult: OpenlawExecutionState,
      overriddenFormatter: (
          Option[FormatterDefinition],
          TemplateExecutionResult
      ) => Option[Formatter]
  ): Result[mutable.Buffer[AgreementElement]] =
    elements match {
      case Nil =>
        Success(renderedElements)
      case element :: rest =>
        getAgreementElementsFromElement(
          renderedElements,
          element,
          executionResult,
          overriddenFormatter
        ) match {
          case Success(list) =>
            getAgreementElements(
              list,
              rest,
              executionResult,
              overriddenFormatter
            )
          case f => f
        }
    }

  private def getAgreementElementsFromElement(
      renderedElements: mutable.Buffer[AgreementElement],
      element: TemplatePart,
      executionResult: OpenlawExecutionState,
      overriddenFormatter: (
          Option[FormatterDefinition],
          TemplateExecutionResult
      ) => Option[Formatter]
  ): Result[mutable.Buffer[AgreementElement]] =
    element match {
      case t: Table =>
        for {
          headerElements <- t.header
            .map(entry =>
              getAgreementElements(
                mutable.Buffer(),
                entry.elems,
                executionResult,
                overriddenFormatter
              ).map(_.toList)
            )
            .sequence
          rowElements <- t.rows
            .map(seq =>
              seq
                .map(entry =>
                  getAgreementElements(
                    mutable.Buffer(),
                    entry.elems,
                    executionResult,
                    overriddenFormatter
                  ).map(_.toList)
                )
                .sequence
            )
            .sequence
        } yield {
          if (headerElements.isEmpty) {
            val nbElements = renderedElements.length
            val lastOption =
              if (nbElements === 0) None
              else Some(renderedElements(nbElements - 1))
            val beforeLastOption =
              if (nbElements < 2) None
              else Some(renderedElements(nbElements - 2))
            (lastOption, beforeLastOption) match {
              case (
                  Some(FreeText(Text(str))),
                  Some(previousTable: TableElement)
                  ) if str.trim.isEmpty =>
                renderedElements.remove(renderedElements.length - 2, 2)
                renderedElements.append(
                  previousTable
                    .copy(rows = previousTable.rows ++ rowElements)
                )
                renderedElements
              case (Some(previousTable: TableElement), _) =>
                renderedElements.remove(renderedElements.length - 1)
                renderedElements.append(
                  previousTable
                    .copy(rows = previousTable.rows ++ rowElements)
                )
                renderedElements
              case _ =>
                renderedElements append TableElement(
                  headerElements,
                  t.alignment,
                  rowElements
                )

                renderedElements
            }
          } else {
            renderedElements append TableElement(
              headerElements,
              t.alignment,
              rowElements
            )
            renderedElements
          }
        }

      case TemplateText(textElements) =>
        getAgreementElements(
          renderedElements,
          textElements,
          executionResult,
          overriddenFormatter
        )
      case Text(str) =>
        renderedElements append FreeText(Text(str))
        Success(renderedElements)
      case Em =>
        renderedElements append FreeText(Em)
        Success(renderedElements)
      case Strong =>
        renderedElements append FreeText(Strong)
        Success(renderedElements)
      case Under =>
        renderedElements append FreeText(Under)
        Success(renderedElements)
      case SectionBreak =>
        renderedElements append FreeText(SectionBreak)
        Success(renderedElements)
      case Indent =>
        renderedElements append FreeText(Indent)
        Success(renderedElements)
      case Centered =>
        renderedElements append FreeText(Centered)
        Success(renderedElements)
      case RightAlign =>
        renderedElements append FreeText(RightAlign)
        Success(renderedElements)
      case RightThreeQuarters =>
        renderedElements append FreeText(RightThreeQuarters)
        Success(renderedElements)
      case annotation: HeaderAnnotation =>
        renderedElements append annotation
        Success(renderedElements)
      case annotation: NoteAnnotation =>
        renderedElements append annotation
        Success(renderedElements)
      case variableDefinition: VariableDefinition
          if variableDefinition.isAnonymous =>
        val nbAnonymous =
          executionResult.processedAnonymousVariableCounter.getAndIncrement()
        getAgreementElementsFromElement(
          renderedElements,
          variableDefinition.copy(name =
            VariableName(executionResult.generateAnonymousName(nbAnonymous + 1))
          ),
          executionResult,
          overriddenFormatter
        )
      case variableDefinition: VariableDefinition
          if !variableDefinition.isHidden =>
        executionResult.getAliasOrVariableType(variableDefinition.name) match {
          case Success(variableType: NoShowInFormButRender) =>
            getDependencies(variableDefinition.name, executionResult).flatMap {
              dependencies =>
                generateVariable(
                  variableDefinition.name,
                  Nil,
                  variableDefinition.formatter,
                  executionResult,
                  overriddenFormatter(
                    variableDefinition.formatter,
                    executionResult
                  )
                ).map { list =>
                  renderedElements append VariableElement(
                    variableDefinition.name,
                    Some(variableType),
                    list,
                    dependencies
                  )

                  renderedElements
                }
            }
          case Success(ClauseType) =>
            executionResult.subExecutionsInternal.get(variableDefinition.name) match {
              case Some(subExecution) =>
                getAgreementElements(
                  renderedElements,
                  subExecution.template.block.elems,
                  subExecution,
                  overriddenFormatter
                )
              case None =>
                Success(renderedElements)
            }

          case Success(_: NoShowInForm) =>
            Success(renderedElements)
          case Success(variableType) =>
            getDependencies(variableDefinition.name, executionResult).flatMap {
              dependencies =>
                generateVariable(
                  variableDefinition.name,
                  Nil,
                  variableDefinition.formatter,
                  executionResult,
                  overriddenFormatter(
                    variableDefinition.formatter,
                    executionResult
                  )
                ).map { list =>
                  renderedElements append VariableElement(
                    variableDefinition.name,
                    Some(variableType),
                    list,
                    dependencies
                  )

                  renderedElements
                }
            }
          case Failure(_, _) =>
            // TODO: Should ignore failure?
            Success(renderedElements)
        }

      case ConditionalBlockSet(blocks) =>
        val result = blocks.map {
          case block @ ConditionalBlock(_, _, conditionalExpression) =>
            conditionalExpression
              .evaluate(executionResult)
              .flatMap(
                _.map(
                  VariableType
                    .convert[OpenlawBoolean](_)
                    .map(boolean => block -> boolean)
                ).sequence
              )
        }.sequence

        result.flatMap { booleans =>
          booleans
            .find {
              case Some((_, true)) => true
              case _               => false
            }
            .map {
              case Some((conditionalBlock, _)) =>
                getAgreementElementsFromElement(
                  renderedElements,
                  conditionalBlock,
                  executionResult,
                  overriddenFormatter
                )
              case x => Failure(s"unexpected value: $x")
            }
            .getOrElse(Success(renderedElements))
        }

      case ConditionalBlock(subBlock, elseBlock, conditionalExpression) =>
        conditionalExpression
          .evaluate(executionResult)
          .flatMap(_.map(VariableType.convert[OpenlawBoolean](_)).sequence)
          .flatMap {
            case Some(true) =>
              conditionalExpression.variables(executionResult).flatMap {
                variables =>
                  val dependencies = variables.map(_.name)
                  getAgreementElements(
                    renderedElements ++ List(
                      ConditionalStart(dependencies = dependencies)
                    ),
                    subBlock.elems,
                    executionResult,
                    overriddenFormatter
                  ).map(_ ++ List(ConditionalEnd(dependencies)))
              }

            case _ =>
              conditionalExpression.variables(executionResult).flatMap {
                variables =>
                  val dependencies = variables.map(_.name)
                  elseBlock
                    .map(block =>
                      getAgreementElements(
                        renderedElements ++ List(
                          ConditionalStart(dependencies = dependencies)
                        ),
                        block.elems,
                        executionResult,
                        overriddenFormatter
                      ).map(_ ++ List(ConditionalEnd(dependencies)))
                    )
                    .getOrElse(Success(renderedElements))
              }
          }

      case ForEachBlock(_, expression, subBlock) =>
        (for {
          valueOpt <- expression.evaluate(executionResult)
          list <- valueOpt
            .map(value =>
              VariableType.convert[CollectionValue](value).map(_.list)
            )
            .sequence
        } yield {
          val collection = list.getOrElse(Seq.empty)
          collection.foldLeft(Success(renderedElements))((subElements, _) => {
            val subExecution =
              executionResult.finishedEmbeddedExecutions.remove(0)
            subElements.flatMap(
              getAgreementElements(
                _,
                subBlock.elems,
                subExecution,
                overriddenFormatter
              )
            )
          })
        }).flatten

      case section @ Section(uuid, definition, lvl) =>
        val resetNumberingResult = definition
          .flatMap(_.parameters)
          .flatMap(_.parameterMap.toMap.get("numbering"))
          .map {
            case OneValueParameter(expr) =>
              expr
                .evaluate(executionResult)
                .flatMap(
                  _.map(
                    VariableType.convert[OpenlawBigDecimal](_).map(b => b.toInt)
                  ).sequence
                )
            case _ =>
              Success(None)
          }
          .sequence
          .map(_.flatten)

        val overrideSymbol = section.overrideSymbol(executionResult)
        val overrideFormat = section.overrideFormat(executionResult)
        val numberResult = executionResult.allProcessedSections
          .collectFirst { case (s, n) if s === section => Success(n) }
          .getOrElse(
            Failure(
              "unexpected condition, section not found in processed sections"
            )
          )

        (for {
          resetNumbering <- resetNumberingResult
          number <- numberResult
          value <- definition
            .flatMap(definition => executionResult.getVariable(definition.name))
            .flatMap(_.evaluate(executionResult).sequence)
            .sequence
        } yield value match {
          case Some(value: SectionInfo) =>
            for {
              overrideSymbolValue <- overrideSymbol
              overrideFormatValue <- overrideFormat
            } yield {
              renderedElements append SectionElement(
                value.numbering,
                lvl,
                number,
                resetNumbering,
                overrideSymbolValue,
                overrideFormatValue
              )

              renderedElements
            }

          case None =>
            val name = executionResult.sectionNameMapping(uuid)
            val result = executionResult
              .getVariable(name)
              .flatMap(_.evaluate(executionResult).sequence)
              .sequence

            result.flatMap {
              case Some(v) =>
                for {
                  overrideSymbolValue <- overrideSymbol
                  overrideFormatValue <- overrideFormat
                  info <- VariableType.convert[SectionInfo](v)
                } yield {
                  renderedElements.append(
                    SectionElement(
                      info.numbering,
                      lvl,
                      number,
                      resetNumbering,
                      overrideSymbolValue,
                      overrideFormatValue
                    )
                  )

                  renderedElements
                }

              case None =>
                Failure(
                  "Section referenced before it has been rendered. The executor can't guess the section number before rendering it yet."
                )
            }
          case Some(v) =>
            Failure(
              s"error while rendering sections the variable should be a section but is ${v.getClass.getSimpleName}"
            )
        }).flatten

      case VariableMember(name, keys, formatter) =>
        val variableFormatterDefinition =
          executionResult.getVariable(name).flatMap(_.formatter)
        val definition =
          executionResult.getVariable(name).map(_.varType(executionResult))
        getDependencies(name, executionResult).flatMap { seq =>
          generateVariable(
            name,
            keys,
            formatter,
            executionResult,
            overriddenFormatter(variableFormatterDefinition, executionResult)
          ).map { variable =>
            renderedElements.append(
              VariableElement(name, definition, variable, seq)
            )

            renderedElements
          }
        }
      case ExpressionElement(expr, formatter) =>
        generateExpression(
          expr,
          formatter,
          executionResult,
          overriddenFormatter(None, executionResult)
        ).map(elems => renderedElements ++ elems)
      case _ =>
        Success(renderedElements)
    }

  private def getDependencies(
      name: VariableName,
      executionResult: TemplateExecutionResult
  ): Result[List[String]] =
    executionResult.getAlias(name) match {
      case Some(alias) =>
        alias.variables(executionResult).map(_.map(_.name))
      case None =>
        executionResult.getVariable(name) match {
          case Some(_) => Success(List(name.name))
          case None    => Success(Nil)
        }
    }

  private def generateExpression(
      expression: Expression,
      formatterDefinition: Option[FormatterDefinition],
      executionResult: TemplateExecutionResult,
      overriddenFormatter: Option[Formatter]
  ): Result[List[AgreementElement]] =
    for {
      valueOpt <- expression.evaluate(executionResult)
      expressionType <- expression.expressionType(executionResult)
      result <- format(
        expressionType,
        formatterDefinition,
        valueOpt,
        expression,
        executionResult,
        overriddenFormatter
      )
    } yield result

  private def format(
      olType: VariableType,
      formatter: Option[FormatterDefinition],
      value: Option[OpenlawValue],
      expression: Expression,
      executionResult: TemplateExecutionResult,
      overriddenFormatter: Option[Formatter]
  ): Result[List[AgreementElement]] =
    for {
      f <- overriddenFormatter
        .map(Success(_))
        .orElse(formatter.map(olType.getFormatter(_, executionResult)))
        .getOrElse(Success(olType.defaultFormatter))
      result <- value
        .map(f.format(expression, _, executionResult))
        .getOrElse(Success(f.missingValueFormat(expression)))
    } yield result

  private def generateVariable(
      name: VariableName,
      keys: List[VariableMemberKey],
      formatter: Option[FormatterDefinition],
      executionResult: TemplateExecutionResult,
      overriddenFormatter: Option[Formatter]
  ): Result[List[AgreementElement]] =
    for {
      varType <- executionResult.getAliasOrVariableType(name)
      valueOpt <- executionResult
        .getExpression(name)
        .map(expression => expression.evaluate(executionResult))
        .sequence
        .map(_.flatten)
      keysType <- varType.keysType(keys, name, executionResult)
      keysValue <- valueOpt
        .map(varType.access(_, name, keys, executionResult))
        .sequence
        .map(_.flatten)
      result <- format(
        keysType,
        formatter,
        keysValue,
        name,
        executionResult,
        overriddenFormatter
      )
    } yield result

  override def withRedefinition(
      redefinition: VariableRedefinition
  ): CompiledAgreement = this.copy(redefinition = redefinition)
}
