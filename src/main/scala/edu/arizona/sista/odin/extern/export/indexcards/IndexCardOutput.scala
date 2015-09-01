package edu.arizona.sista.odin.extern.export.indexcards

import java.io.{FileWriter, BufferedWriter, PrintWriter, File}
import java.util.Date

import edu.arizona.sista.reach.FriesEntry
import edu.arizona.sista.reach.mentions._
import edu.arizona.sista.odin.{RelationMention, Mention}
import edu.arizona.sista.odin.extern.export.JsonOutputter
import org.json4s.native.Serialization

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import JsonOutputter._

/**
 * Defines classes and methods used to build and output the index card format.
 *   Written by Mihai Surdeanu. 8/27/2015.
 */
class IndexCardOutput extends JsonOutputter {
  /**
   * Writes the given mentions to output files in Fries JSON format.
   * Separate output files are written for sentences, entities, and events.
   * Each output file is prefixed with the given prefix string.
   */
  override def writeJSON (paperId:String,
                          allMentions:Seq[Mention],
                          paperPassages:Seq[FriesEntry],
                          startTime:Date,
                          endTime:Date,
                          outFilePrefix:String): Unit = {
    // we create a separate directory for each paper, and store each index card as a separate file
    val dir = new File(outFilePrefix)
    if(! dir.exists()) {
      if (! dir.mkdirs()) {
        throw new RuntimeException(s"ERROR: failed to create output directory $outFilePrefix!")
      }
    } else {
      // delete all files in this directory
      val files = dir.listFiles()
      for(file <- files) file.delete()
    }

    // index cards are generated here
    val cards = mkCards(paperId, allMentions, startTime, endTime)

    // save one index card per file
    var count = 1
    for(card <- cards) {
      val outFile = new File(outFilePrefix + File.separator + mkIndexCardFileName(paperId, count) + ".json")
      writeJsonToFile(card, outFile)
      count += 1
    }

  }

  def mkIndexCardFileName(paperId:String, count:Int):String = s"$paperId-$ORGANIZATION-$RUN_ID-$count"

  /** Convert the entire output data structure to JSON and write it to the given file. */
  private def writeJsonToFile (model:PropMap, outFile:File) = {
    val out:PrintWriter = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))
    Serialization.writePretty(model, out)
    out.println()                           // add final newline which serialization omits
    out.flush()
    out.close()
  }

  /** Creates index cards from all events read for this paper */
  def mkCards(paperId:String,
              allMentions:Seq[Mention],
              startTime:Date,
              endTime:Date):Iterable[PropMap] = {
    val cards = new ListBuffer[PropMap]

    //
    // this needs to be done in 2 passes:
    //   first, save recursive events
    //   then, save simple events that are not part of recursive events
    //

    // keeps just events
    val eventMentions = allMentions.filter(isEventMention)
    // println(s"Found ${eventMentions.size} events.")
    // keeps track of simple events that participate in regulations
    val simpleEventsInRegs = new mutable.HashSet[Mention]()

    // first, print all regulation events
    for(mention <- eventMentions) {
      if (REGULATION_EVENTS.contains(mention.label)) {
        val bioMention = mention.toBioMention
        val card = mkRegulationIndexCard(bioMention, simpleEventsInRegs)
        card.foreach(c => {
          addMeta(c, bioMention, paperId, startTime, endTime)
          cards += c
        })

      }
    }
    /*
    println(s"controlled size: ${simpleEventsInRegs.size}")
    for(e <- eventMentions) {
      if(simpleEventsInRegs.contains(e)) {
        println(s"Found ${e.label}")
      }
    }
    */

    // now, print everything else that wasn't printed already
    for(mention <- eventMentions) {
      if (! REGULATION_EVENTS.contains(mention.label) && ! simpleEventsInRegs.contains(mention)) {
        val bioMention = mention.toBioMention
        val card = mkIndexCard(mention.toBioMention)
        card.foreach(c => {
          addMeta(c, bioMention, paperId, startTime, endTime)
          cards += c
        })
      }
    }

    cards.toList
  }

  def mkIndexCard(mention:BioMention):Option[PropMap] = {
    val eventType = mkEventType(mention.label)
    val f = new PropMap
    val ex = eventType match {
      case "protein-modification" => mkModificationIndexCard(mention)
      case "complex-assembly" => mkBindingIndexCard(mention)
      case "translocation" => mkTranslocationIndexCard(mention)
      case "activation" => mkActivationIndexCard(mention)
      case "regulation" => throw new RuntimeException("ERROR: regulation events must be saved before!")
      case _ => throw new RuntimeException(s"ERROR: event type $eventType not supported!")
    }
    if(ex.isDefined) {
      mkHedging(ex.get, mention)
      mkContext(ex.get, mention)
      f("extracted_information") = ex.get
      Some(f)
    } else {
      None
    }
  }

  def mkArgument(arg:BioMention):Any = {
    val argType = mkArgType(arg)
    argType match {
      case "entity" => mkSingleArgument(arg)
      case "complex" => mkComplexArgument(arg.asInstanceOf[RelationMention])
      case _ => throw new RuntimeException(s"ERROR: argument type $argType not supported!")
    }
  }

  def mkSingleArgument(arg:BioMention):PropMap = {
    val f = new PropMap
    f("entity_text") = arg.text
    f("entity_type") = arg.displayLabel.toLowerCase
    if(arg.isGrounded) f("identifier") = mkIdentifier(arg.xref.get)
    if(hasFeatures(arg)) f("features") = mkFeatures(arg)
    // TODO: we do not compare against the model; assume everything is new
    f("in_model") = false
    f
  }

  def mkFeatures(arg:BioMention):FrameList = {
    val fl = new FrameList
    arg.modifications.foreach {
      case ptm: PTM => fl += mkPTMFeature(ptm)
      case mut: Mutant => fl += mkMutantFeature(mut)
      case _ => // there may be other features that are not relevant for the index card output
    }
    fl
  }

  def mkPTMFeature(ptm:PTM):PropMap = {
    val f = new PropMap
    f("feature_type") = "modification"
    f("modification_type") = ptm.label.toLowerCase
    if(ptm.site.isDefined)
      f("position") = ptm.site.get.text
    f
  }

  def mkMutantFeature(m:Mutant):PropMap = {
    val f = new PropMap
    f("feature_type") = "mutation"
    if(m.evidence.text.length > 0)
      f("position") = m.evidence.text
    f
  }

  def mkComplexArgument(complex:RelationMention):FrameList = {
    val fl = new FrameList
    val participants = complex.arguments.get("theme")
    if(participants.isEmpty) throw new RuntimeException("ERROR: cannot have a complex with 0 participants!")
    participants.get.foreach(p => {
      fl += mkSingleArgument(p.toBioMention)
    })
    fl
  }

  def mkIdentifier(xref:Grounding.Xref):String = {
    xref.namespace + ":" + xref.id
  }

  def mkEventModification(mention:BioMention):PropMap = {
    val f = new PropMap
    f("modification_type") = mention.displayLabel.toLowerCase
    if(mention.arguments.contains("site"))
      f("position") = mention.arguments.get("site").get.head.text
    f
  }

  /** Creates a card for a simple, modification event */
  def mkModificationIndexCard(mention:BioMention,
                              positiveModification:Boolean = true):Option[PropMap] = {
    val f = new PropMap
    // a modification event will have exactly one theme
    f("participant_b") = mkArgument(mention.arguments.get("theme").get.head.toBioMention)
    if(positiveModification)
      f("interaction_type") = "adds_modification"
    else
      f("interaction_type") = "inhibits_modification"
    val mods = new FrameList
    mods += mkEventModification(mention)
    f("modifications") = mods
    Some(f)
  }

  def mkHedging(f:PropMap, mention:BioMention) {
    if(isNegated(mention)) f("negative_information") = true
    else f("negative_information") = false
    if(isHypothesized(mention)) f("hypothesis_information") = true
    else f("hypothesis_information") = false
  }

  def mkContext(f:PropMap, mention:BioMention): Unit = {
    // TODO: add context information here!
  }

  /** Creates a card for a regulation event */
  def mkRegulationIndexCard(mention:BioMention,
                            simpleEventsInRegs:mutable.HashSet[Mention]):Option[PropMap] = {
    if(! mention.arguments.contains("controlled"))
      throw new RuntimeException("ERROR: a regulation event must have a controlled argument!")
    val controlledMention = mention.arguments.get("controlled").get.head
    val controlled = controlledMention.toBioMention

    // INDEX CARD LIMITATION:
    // We only know how to output regulations when controlled is a modification!
    val eventType = mkEventType(controlled.label)
    if(eventType != "protein-modification") return None

    // do not output this event again later, when we output single modifications
    simpleEventsInRegs += controlledMention

    // populate participant_b and the interaction type from the controlled event
    val posMod = mention.label match {
      case "Positive_regulation" => true
      case "Negative_regulation" => false
      case _ => throw new RuntimeException(s"ERROR: unknown regulation event ${mention.label}!")
    }
    val f = mkModificationIndexCard(controlled, positiveModification = posMod)

    // add participant_a from the controller
    if(! mention.arguments.contains("controller"))
      throw new RuntimeException("ERROR: a regulation event must have a controller argument!")
    f.get("participant_a") = mkArgument(mention.arguments.get("controller").get.head.toBioMention)

    f
  }

  /** Creates a card for an activation event */
  def mkActivationIndexCard(mention:BioMention):Option[PropMap] = {
    val f = new PropMap
    // a modification event will have exactly one controller and one controlled
    f("participant_a") = mkArgument(mention.arguments.get("controller").get.head.toBioMention)
    f("participant_b") = mkArgument(mention.arguments.get("controlled").get.head.toBioMention)
    if(mention.label == "Positive_activation")
      f("interaction_type") = "increases_activity"
    else
      f("interaction_type") = "decreases_activity"
    Some(f)
  }

  /** Creates a card for a complex-assembly event */
  def mkBindingIndexCard(mention:BioMention):Option[PropMap] = {
    val f = new PropMap
    f("interaction_type") = "binds"

    val participants = mention.arguments.get("theme").get
    // a Binding events must have at least 2 arguments
    val participantA = participants.head.toBioMention
    f("participant_a") = mkArgument(participantA)
    val participantB = participants.tail
    if(participantB.size == 1) {
      f("participant_b") = mkArgument(participantB.head.toBioMention)
    } else if(participantB.size > 1) {
      // store them all as a single complex.
      // INDEX CARD LIMITATION: This is ugly, but there is no other way with the current format
      val fl = new FrameList
      participantB.foreach(p => {
        fl += mkSingleArgument(p.toBioMention)
      })
      f("participant_b") = fl
    } else {
      throw new RuntimeException("ERROR: A complex assembly event must have at least 2 participants!")
    }

    // add binding sites if present
    if(mention.arguments.contains("site")) {
      val fl = new StringList
      for(site <- mention.arguments.get("site").get) {
        fl += site.text
      }
      f("binding_site") = fl
    }

    Some(f)
  }

  /** Creates a card for a translocation event */
  def mkTranslocationIndexCard(mention:BioMention):Option[PropMap] = {
    val f = new PropMap
    f("interaction_type") = "translocates"
    f("participant_b") = mention.arguments.get("theme").get.head
    if(mention.arguments.contains("source")) {
      addLocation(f, "from", mention.arguments.get("source").get.head.toBioMention)
    }
    if(mention.arguments.contains("destination")) {
      addLocation(f, "to", mention.arguments.get("destination").get.head.toBioMention)
    }
    Some(f)
  }

  def addLocation(f:PropMap, prefix:String, loc:BioMention): Unit = {
    f(prefix + "_location_id") = mkIdentifier(loc.xref.get)
    f(prefix + "_location_text") = loc.text
  }

  def addMeta(f:PropMap,
              mention:BioMention,
              paperId:String,
              startTime:Date,
              endTime:Date): Unit = {
    f("submitter") = COMPONENT
    f("score") = 0
    f("pmc_id") = if(paperId.startsWith("PMC")) paperId.substring(3) else paperId
    f("reader_type") = "machine"
    f("reading_started") = startTime
    f("reading_complete") = endTime
    val ev = new StringList
    ev += mention.text
    f("evidence") = ev
    // TODO: we do not compare against the model; assume everything is new
    f("model_relation") = "extension"
  }

  def startFrame(paperId:String, component:String):PropMap = {
    val f = new PropMap
    f("submitter") = component
    f("score") = 0
    f("pmc_id") = paperId.substring(3)
    f("reader_type") = "machine"

    f("object-type") = "frame"
    val meta = new PropMap
    meta("object-type") = "meta-info"
    meta("component") = component
    f("object-meta") = meta
    f
  }

  /**
   * Returns the given mentions in the index-card JSON format, as one big string.
   * All index cards are concatenated into a single JSON string.
   */
  override def toJSON (paperId:String,
                       allMentions:Seq[Mention],
                       paperPassages:Seq[FriesEntry],
                       startTime:Date,
                       endTime:Date,
                       outFilePrefix:String): String = {
    // index cards are generated here
    val cards = mkCards(paperId, allMentions, startTime, endTime)

    val uniModel:PropMap = new PropMap
    uniModel("cards") = cards
    writeJsonToString(uniModel)
  }
}

