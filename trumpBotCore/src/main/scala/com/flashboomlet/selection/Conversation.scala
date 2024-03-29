package com.flashboomlet.selection

import com.flashboomlet.data.ConversationState
import com.flashboomlet.data.Response
import com.flashboomlet.db.MongoDatabaseDriver
import com.flashboomlet.preprocessing.ClassifiedInput
import com.typesafe.scalalogging.LazyLogging

/**
  * Created by ttlynch on 9/17/16.
  *
  * Conversation will select responses from the database and output a response
  */
class Conversation extends LazyLogging {

  val db: MongoDatabaseDriver = new MongoDatabaseDriver()
  val stateMachine: UpdateState = new UpdateState()
  val em: EscapeMode = new EscapeMode()
  val tm: TroubleMode = new TroubleMode()
  val r = scala.util.Random

  /**
    * Generate Response is a request from the user for a response.
    *
    * @param ci the current conversation state of the conversation
    * @return a response to be sent to the user
    */
  def GenerateResponse(ci: ClassifiedInput): String = {

    val id = ci.conversationId // Assuming the conversation id will always be one.
    val pastConversations = db.getConversationStates(id)
    // Current State of the Conversation
    val cs = stateMachine.updateState(ci)
    logger.info(s"Conversation State: \n {}", cs.toString)

    val response = if (cs.conversationState == 0) {
      // If the conversation State is triggered to be over then
      ConversationEnd(cs, pastConversations)
    } else if (cs.conversationState == 1) {
      // if the total amount of conversational topics of this conversation exceeds 5, leave exit mode.
      Conversation(cs, pastConversations)
    } else {
      ConversationStart(cs)
    }

    // Update Conversation State
    val finalCS = updateResponseMsg(cs, response)
    // Insert Conversation State into DB
    db.insertConversationState(finalCS)
    // Return Response
    response
  }

  /**
    * Conversation Start is the starting stage of the conversation. In here, Trump attempts to
    * gain control of the conversation.
    *
    * @param cs the current conversation state of the conversation
    * @return a response to the user
    */
  private def ConversationStart(cs: ConversationState): String = {
    if (cs.topicResponseCount < 1 ) {
      "Hello, I am Donald J. Trump, the Republican Presidential Nominee. How are you doing?"
    } else {
      // update conversation state to get out of the start mode.
      if (cs.sentimentClass == "Negative" && cs.sentimentConfidence > 51) {
        val response: String = "I am Making America Great Again!"
        response + " What questions can I answer for you today?"
      } else {
        val response: String = "I am glad to hear that. I am Making America Great Again!"
        response + " What questions can I answer for you today?"
      }
    }
  }

  /**
    * The conversation is the bulk of the content. It is where the magic happens.
    *
    * @param cs the current conversation state of the conversation
    * @return a response to the user
    */
  private def Conversation(
    cs: ConversationState,
    pastStates: List[ConversationState]): String = {

    if (cs.transitionState) {
      randomConversationStarter()
    } else {
      // Select the response from the collection of responses that most closely matches a response
      // from the user.
      if (cs.escapeMode) { /* Order of these if's matters!!! */
        em.escapeMode(cs)
      } else if (cs.troubleMode) {
        tm.troubleMode(cs)
      } else {
        // If greater than 3 replies, then transition to a new topic.
        val transition: String = if (cs.transitionState) {
        // TODO: Implement a transition trigger lol
          // Conversation State can lead to a bomb transition
          " What other questions do you have for me?"
        } else {
          ""
        }
        getResponse(cs, pastStates) + transition
      }
    }
  }

  /**
    * Get Response gets a response to send to the end user. Let's be honest,
    * this part is pure magic. Nobody really knows what is going on.
    *
    * @param cs the current conversation state
    * @param pastStates the past states of the conversation state
    * @return
    */
  private def getResponse(
    cs: ConversationState,
    pastStates: List[ConversationState]): String = {

    val responses: List[Response] = db.getResponses()
    val topics = cs.topics
    // TODO: Insert code to get out if there is a canned trigger first
    val cannedList = responses.filter(s => cs.message.contains(s.cannedTrigger))
    if(cannedList.nonEmpty) {
      // We have a canned trigger to fire off!
      cannedList.head.content
    }
    else {
      val respFiltered = responses.filter(_.primaryTopic == cs.topic)
      val pastResponses = pastStates.map(_.responseMessage)

      val responsesWithSimilarity = respFiltered.map { r =>
        (
        r,
        percentSimilar(topics, r.topics),
        pastResponses
        )
      }

      val positiveFlag: Boolean = cs.sentimentClass == "Positive"

      val validResponses = responsesWithSimilarity
      .filter(s => !s._3.contains(s._1.content))
      .sortBy(_._2).reverse

      println(validResponses)

      if (validResponses.length < 1) {
        // Well we messed up, time to leave

        val randomTopic = selectRandomTopic()
        val takeTwo = responses.filter(_.primaryTopic == randomTopic)
        if (takeTwo.nonEmpty) {
          takeTwo.head.content
        }
        else {
          ConversationEnd(cs, pastStates)
        }
      } else {
        validResponses.head._1.content
      }
    }
  }

  /**
    * Determines how similar the data is
    *
    * @param topics the topics of the current at question state
    * @param responseTopics the topics of a possible response
    * @return the percent of similarity between the two
    */
  private def percentSimilar(topics: List[String], responseTopics: List[String]): Double = {
    val len: Double = topics.length
    responseTopics.map( r => (r, topics)).count( obj => obj._2.contains(obj._1)) / len
  }

  /**
    * Conversation end is the ending of a conversation. It can be triggered by multiple topics
    * being covered or by an escape mode.
    *
    * @param cs the current conversation state of the conversation
    * @return a response to send to the user
    */
  private def ConversationEnd(
      cs: ConversationState,
      pastStates: List[ConversationState]): String = {

    // Find average sentiment of the conversation by the other person to gage interest.
    val sentiments: List[(Long, String)] = pastStates.map(s =>
      (s.sentimentConfidence, s.sentimentClass))

    val averageSentiment: Double = Util.calculateAverageSentiment(
      (cs.sentimentConfidence, cs.sentimentClass) +: sentiments)

    val sentThreshold = -0.5

    if (averageSentiment < sentThreshold) {
      // escape mode
      em.escapeMode(cs)
    } else if (averageSentiment > sentThreshold) {
      //    thank for support, ask for donation, tell them to preach the good word.
      val response: String = "Thank you for your support! I greatly appreciate it. Together we " +
      "will will make America Great Again! If you would like to help out with my campaign, " +
      "the easiest way to get involved would be to go to https://www.donaldjtrump.com/ and " +
      "make a donation. Together we will beat corrupt Hillary!"
      response
    } else {
      // leave in a civil manner. Refer them to the website to find out more details.
      val response: String = "I was a pleasure talking to you. I have an event I must attend so " +
      "I am signing off now. If you have any more questions please drop by my website. I have " +
      "all of my policies outlined. I am sure that you will find the Answers to all of your " +
      "questions there. You will surely see why a vote for my is far better than anyone else! " +
      " Make America Great Again! - Donald J. Trump"
      response
    }
  }

  /**
    * Random Conversation Starter will select a random response with a lot of topics that are the
    * the same to respond with. The response is guaranteed to be unique and not used along with on
    * a topic that has not been used yet.
    *
    * @return a random response from the database that has not be used prior.
    */
  private def randomConversationStarter(): String = {
    " You bring up a lot of great points. Thank you for sharing! "
  }

  /**
    * Update Response Message will update the conversation state with the response message
    *
    * @param cs the current conversation state
    * @param msg the msg that will be the response
    * @return an updated conversation state
    */
  private def updateResponseMsg(cs: ConversationState, msg: String): ConversationState = {
    ConversationState(
      conversationId = cs.conversationId,
      messageId = cs.messageId,
      lengthState = cs.lengthState,
      sentimentConfidence = cs.sentimentConfidence,
      sentimentClass = cs.sentimentClass,
      topic = cs.topic,
      topics = cs.topics,
      conversationState = cs.conversationState,
      transitionState = cs.transitionState,
      topicResponseCount = cs.topicResponseCount,
      troubleMode = cs.troubleMode,
      escapeMode = cs.escapeMode,
      tangent = cs.tangent,
      parentTopic = cs.parentTopic,
      message = cs.message,
      responseMessage = msg,
      tangentCount = cs.tangentCount
    )
  }

  /**
    * Select Random Topic Randomly selects a topic or sub topic from the database of
    * known responses.
    *
    * @return a random topic from the response database
    */
  def selectRandomTopic(): String = {
    // TODO: Update this list with topic changes if need be
    val topics = List(
      "wall",
      "terrorism" ,
      "china" ,
      "taxes" ,
      "children" ,
      "immigration" ,
      "trade" ,
      "police" ,
      "guns" ,
      "education" ,
      "jobs" ,
      "myself",
      "Crooked Hillary",
      "abortion",
      "health care",
      "foreign policy")

    val len = topics.length
    val i = r.nextInt(len)
    topics(i)
  }
}
