/**
 * Copyright (c) 2014-2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import com.debiki.core.EditedSettings.MaxNumFirstPosts
import com.debiki.core.Prelude._
import com.debiki.core.PageParts.FirstReplyNr
import controllers.EditController
import debiki._
import debiki.EdHttp._
import ed.server.notf.NotificationGenerator
import ed.server.pubsub.StorePatchMessage
import play.api.libs.json.{JsObject, JsValue}
import play.{api => p}
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer
import PostsDao._
import ed.server.auth.Authz
import org.scalactic.{Bad, Good, One, Or}
import math.max
import talkyard.server.{IfCached, PostRendererSettings}


case class InsertPostResult(storePatchJson: JsObject, post: Post, reviewTask: Option[ReviewTask])


/** Loads and saves pages and page parts (e.g. posts and patches).
  *
  * (There's also a class PageDao (with no 's' in the name) that focuses on
  * one specific single page.)
  *
  * SHOULD make the full text search indexer work again
  */
trait PostsDao {
  self: SiteDao =>

  import context.globals

  // 3 minutes
  val LastChatMessageRecentMs: UnixMillis = 3 * 60 * 1000


  def insertReply(textAndHtml: TextAndHtml, pageId: PageId, replyToPostNrs: Set[PostNr],
        postType: PostType, byWho: Who, spamRelReqStuff: SpamRelReqStuff)
        : InsertPostResult = {

    val authorId = byWho.id
    val now = globals.now()

    // Note: Fairly similar to createNewChatMessage() just below. [4UYKF21]

    if (textAndHtml.safeHtml.trim.isEmpty)
      throwBadReq("DwE6KEF2", "Empty reply")

    // Later: create 1 post of type multireply, with no text, per replied-to post,
    // and one post for the actual text and resulting location of this post.
    // Disabling for now, so I won't have to parse dw2_posts.multireply and convert
    // to many rows.
    if (replyToPostNrs.size > 1)
      throwNotImplemented("EsE7GKX2", o"""Please reply to one single person only.
        Multireplies temporarily disabled, sorry""")

    quickCheckIfSpamThenThrow(byWho, textAndHtml, spamRelReqStuff)

    val (newPost, author, notifications, anyReviewTask) = readWriteTransaction { tx =>
      insertReplyImpl(textAndHtml, pageId, replyToPostNrs, postType, byWho, spamRelReqStuff,
        now, authorId, tx)
    }

    refreshPageInMemCache(pageId)

    val storePatchJson = jsonMaker.makeStorePatch(newPost, author, showHidden = true)
    pubSub.publish(StorePatchMessage(siteId, pageId, storePatchJson, notifications),
      byId = author.id)

    InsertPostResult(storePatchJson, newPost, anyReviewTask)
  }


  def insertReplyImpl(textAndHtml: TextAndHtml, pageId: PageId, replyToPostNrs: Set[PostNr],
        postType: PostType, byWho: Who, spamRelReqStuff: SpamRelReqStuff,
        now: When, authorId: UserId, transaction: SiteTransaction, skipNotifications: Boolean = false)
        : (Post, User, Notifications, Option[ReviewTask]) = {

    val authorAndLevels = loadUserAndLevels(byWho, transaction)
    val author = authorAndLevels.user
    val page = PageDao(pageId, transaction)
    val replyToPosts = page.parts.getPostsAllOrError(replyToPostNrs) getOrIfBad  { missingPostNr =>
      throwNotFound(s"Post nr $missingPostNr not found", "EdE4JK2RJ")
    }

    dieOrThrowNoUnless(Authz.mayPostReply(authorAndLevels, transaction.loadGroupIds(author),
      postType, page.meta, replyToPosts, transaction.loadAnyPrivateGroupTalkMembers(page.meta),
      transaction.loadCategoryPathRootLast(page.meta.categoryId),
      transaction.loadPermsOnPages()), "EdEMAY0RE")

    if (page.role.isChat)
      throwForbidden("EsE50WG4", s"Page '${page.id}' is a chat page; cannot post normal replies")

    // Some dupl code [3GTKYA02]
    val uniqueId = transaction.nextPostId()
    val postNr = page.parts.highestReplyNr.map(_ + 1).map(max(FirstReplyNr, _)) getOrElse FirstReplyNr
    val commonAncestorNr = page.parts.findCommonAncestorNr(replyToPostNrs.toSeq)
    val anyParent =
      if (commonAncestorNr == PageParts.NoNr) {
        // Flat chat comments might not reply to anyone in particular.
        // On embedded comments pages, there's no Original Post, so top level comments
        // have no parent post.
        if (postType != PostType.Flat && postType != PostType.BottomComment &&
            postType != PostType.CompletedForm && page.role != PageRole.EmbeddedComments)
          throwBadReq("DwE2CGW7", "Post lacks parent id")
        else
          None
      }
      else {
        val anyParent = page.parts.postByNr(commonAncestorNr)
        if (anyParent.isEmpty) {
          throwBadReq("DwEe8HD36", o"""Cannot reply to common ancestor post '$commonAncestorNr';
              it does not exist""")
        }
        anyParent
      }
    if (anyParent.exists(_.deletedStatus.isDeleted))
      throwForbidden(
        "The parent post has been deleted; cannot reply to a deleted post", "DwE5KDE7")

    val (reviewReasons: Seq[ReviewReason], shallApprove) =
      throwOrFindReviewPostReasons(page.meta, authorAndLevels, transaction)

    val approverId =
      if (author.isStaff) {
        dieIf(!shallApprove, "EsE5903")
        Some(author.id)
      }
      else if (shallApprove) Some(SystemUserId)
      else None

    val newPost = Post.create(
      uniqueId = uniqueId,
      pageId = pageId,
      postNr = postNr,
      parent = anyParent,
      multireplyPostNrs = (replyToPostNrs.size > 1) ? replyToPostNrs | Set.empty,
      postType = postType,
      createdAt = now.toJavaDate,
      createdById = authorId,
      source = textAndHtml.text,
      htmlSanitized = textAndHtml.safeHtml,
      approvedById = approverId)

    val shallBumpPage = !page.isClosed && shallApprove
    val numNewOpRepliesVisible = (shallApprove && newPost.isOrigPostReply) ? 1 | 0
    val newFrequentPosterIds: Seq[UserId] =
      if (shallApprove)
        PageParts.findFrequentPosters(newPost +: page.parts.allPosts,
          ignoreIds = Set(page.meta.authorId, authorId))
      else
        page.meta.frequentPosterIds

    val oldMeta = page.meta
    val newMeta = oldMeta.copy(
      bumpedAt = shallBumpPage ? Option(now.toJavaDate) | oldMeta.bumpedAt,
      lastReplyAt = shallApprove ? Option(now.toJavaDate) | oldMeta.lastReplyAt,
      lastReplyById = shallApprove ? Option(authorId) | oldMeta.lastReplyById,
      frequentPosterIds = newFrequentPosterIds,
      numRepliesVisible = page.parts.numRepliesVisible + (shallApprove ? 1 | 0),
      numRepliesTotal = page.parts.numRepliesTotal + 1,
      numPostsTotal = page.parts.numPostsTotal + 1,
      numOrigPostRepliesVisible = page.parts.numOrigPostRepliesVisible + numNewOpRepliesVisible,
      version = oldMeta.version + 1)

    val uploadRefs = findUploadRefsInPost(newPost)

    val auditLogEntry = AuditLogEntry(
      siteId = siteId,
      id = AuditLogEntry.UnassignedId,
      didWhat = AuditLogEntryType.NewReply,
      doerId = authorId,
      doneAt = now.toJavaDate,
      browserIdData = byWho.browserIdData,
      pageId = Some(pageId),
      uniquePostId = Some(newPost.id),
      postNr = Some(newPost.nr),
      targetPageId = anyParent.map(_.pageId),
      targetUniquePostId = anyParent.map(_.id),
      targetPostNr = anyParent.map(_.nr),
      targetUserId = anyParent.map(_.createdById))

    val anyReviewTask = if (reviewReasons.isEmpty) None
    else Some(ReviewTask(
      id = transaction.nextReviewTaskId(),
      reasons = reviewReasons.to[immutable.Seq],
      createdById = SystemUserId,
      createdAt = now.toJavaDate,
      createdAtRevNr = Some(newPost.currentRevisionNr),
      maybeBadUserId = authorId,
      postId = Some(newPost.id),
      postNr = Some(newPost.nr)))

    val stats = UserStats(
      authorId,
      lastSeenAt = now,
      lastPostedAt = Some(now),
      firstDiscourseReplyAt = Some(now),
      numDiscourseRepliesPosted = 1,
      numDiscourseTopicsRepliedIn = 0) // SHOULD update properly

    addUserStats(stats)(transaction)
    transaction.insertPost(newPost)
    transaction.indexPostsSoon(newPost)
    transaction.spamCheckPostsSoon(byWho, spamRelReqStuff, newPost)
    transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = shallApprove)
    if (shallApprove) {
      val pagePartsInclNewPost = PreLoadedPageParts(pageId, page.parts.allPosts :+ newPost)
      updatePagePopularity(pagePartsInclNewPost, transaction)
    }
    uploadRefs foreach { uploadRef =>
      transaction.insertUploadedFileReference(newPost.id, uploadRef, authorId)
    }
    insertAuditLogEntry(auditLogEntry, transaction)
    anyReviewTask.foreach(transaction.upsertReviewTask)

    val notifications =
      if (skipNotifications) Notifications.None
      else NotificationGenerator(transaction).generateForNewPost(page, newPost)
    transaction.saveDeleteNotifications(notifications)

    (newPost, author, notifications, anyReviewTask)
  }


  /** Returns (review-reasons, shall-approve).
    */
  def throwOrFindReviewPostReasons(pageMeta: PageMeta, author: UserAndLevels,
        transaction: SiteTransaction): (Seq[ReviewReason], Boolean) = {
    throwOrFindReviewReasonsImpl(author, Some(pageMeta), newPageRole = None, transaction)
  }


  def throwOrFindReviewReasonsImpl(author: UserAndLevels, pageMeta: Option[PageMeta],
        newPageRole: Option[PageRole], transaction: SiteTransaction)
        : (Seq[ReviewReason], Boolean) = {
    if (author.isStaff)
      return (Nil, true)

    // Don't review direct messages — then all staff would see them. Instead, only non-threat
    // users with level >= Basic may post private messages to non-staff people.
    if (pageMeta.map(_.pageRole).contains(PageRole.FormalMessage))
      return (Nil, true)

    val reviewReasons = mutable.ArrayBuffer[ReviewReason]()
    var autoApprove = true

    author.threatLevel match {
      case ThreatLevel.HopefullySafe =>
        // Fine
      case ThreatLevel.MildThreat =>
        reviewReasons.append(ReviewReason.IsByThreatUser)
      case ThreatLevel.ModerateThreat =>
        reviewReasons.append(ReviewReason.IsByThreatUser)
        autoApprove = false
      case ThreatLevel.SevereThreat =>
        // This can happen if the threat level was changed during the processing of the current
        // request (after the initial security checks).
        throwForbidden("EsE5Y80G2_", "Forbidden")
    }

    // COULD add a users3 table status field instead, and update it on write, which says
    // if the user has too many pending comments / edits. Then could thow that status
    // client side, withouth having to run the below queries again and again.
    // Also, would be simpler to move all this logic to ed.server.auth.Authz.

    // Don't review, but auto-approve, user-to-user messages. Staff aren't supposed to read
    // those, unless the receiver reports the message.
    // Later: Create a review task anyway, for admins only, if the user is considered a mild threat?
    // And throw-forbidden if considered a moderate threat.
    if (newPageRole.contains(PageRole.FormalMessage)) {
      // For now, just this basic check to prevent too-often-flagged people from posting priv msgs
      // to non-staff. COULD allow messages to staff, but currently we here don't have access
      // to the page members, so we don't know if they are staff.
      // Later: auto bump threat level to ModerateThreat, then MessagesDao will do all this
      // automatically.
      val tasks = transaction.loadReviewTasksAboutUser(author.id, limit = MaxNumFirstPosts,
        OrderBy.MostRecentFirst)
      val numLoaded = tasks.length
      val numPending = tasks.count(_.resolution.isEmpty)
      val numRejected = tasks.count(_.resolution.exists(_.isHarmful))
      if (numLoaded >= 2 && numRejected > (numLoaded / 2)) // for now
        throwForbidden("EsE7YKG2", "Too many rejected comments or edits or something")
      if (numPending > 5) // for now
        throwForbidden("EsE5JK20", "Too many pending review tasks")
      return (Nil, true)
    }

    val settings = loadWholeSiteSettings(transaction)
    val numFirstToAllow = math.min(MaxNumFirstPosts, settings.numFirstPostsToAllow)
    val numFirstToApprove = math.min(MaxNumFirstPosts, settings.numFirstPostsToApprove)
    var numFirstToNotify = math.min(MaxNumFirstPosts, settings.numFirstPostsToReview)

    // Always have staff review a new guest's first two comments, at least,
    // regardless of site settings. [4JKFWP4]
    if (author.user.isGuest && (numFirstToApprove + numFirstToNotify) < 2) {
      numFirstToNotify = 2 - numFirstToApprove
    }

    if ((numFirstToAllow > 0 && numFirstToApprove > 0) || numFirstToNotify > 0) {
      val tasks = transaction.loadReviewTasksAboutUser(author.id, limit = MaxNumFirstPosts,
        OrderBy.OldestFirst)
      val numApproved = tasks.count(_.resolution.exists(_.isFine))
      val numLoaded = tasks.length

      if (numApproved < numFirstToApprove) {
        // This user is still under evaluation (is s/he a spammer or not?).
        autoApprove = false
        if (numLoaded >= numFirstToAllow)
          throwForbidden("_EsE6YKF2_", o"""You cannot post more posts until a moderator has
              approved your first posts""")
      }

      if (numLoaded < math.min(MaxNumFirstPosts, numFirstToApprove + numFirstToNotify)) {
        reviewReasons.append(ReviewReason.IsByNewUser, ReviewReason.NewPost)
      }
      else if (!autoApprove) {
        reviewReasons.append(ReviewReason.IsByNewUser, ReviewReason.NewPost)
      }
    }

    if (pageMeta.exists(_.isClosed)) {
      // The topic won't be bumped, so no one might see this post, so staff should review it.
      // Could skip this if the user is trusted.
      reviewReasons.append(ReviewReason.NoBumpPost)
    }

    (reviewReasons, autoApprove)
  }


  /** If the chat message author just posted another chat message, just above, no other
    * messages in between — then we'll append this new message to the old one, instead
    * of creating a new different chat message.
    */
  def insertChatMessage(textAndHtml: TextAndHtml, pageId: PageId, byWho: Who,
        spamRelReqStuff: SpamRelReqStuff): InsertPostResult = {
    val authorId = byWho.id

    if (textAndHtml.safeHtml.trim.isEmpty)
      throwBadReq("DwE2U3K8", "Empty chat message")

    quickCheckIfSpamThenThrow(byWho, textAndHtml, spamRelReqStuff)

    val (post, author, notifications) = readWriteTransaction { transaction =>
      val authorAndLevels = loadUserAndLevels(byWho, transaction)
      val author = authorAndLevels.user

      SHOULD_OPTIMIZE // don't load all posts [2GKF0S6], because this is a chat, could be too many.
      val page = PageDao(pageId, transaction)
      val replyToPosts = Nil // currently cannot reply to specific posts, in the chat. [7YKDW3]

      dieOrThrowNoUnless(Authz.mayPostReply(authorAndLevels, transaction.loadGroupIds(author),
        PostType.ChatMessage, page.meta, Nil, transaction.loadAnyPrivateGroupTalkMembers(page.meta),
        transaction.loadCategoryPathRootLast(page.meta.categoryId),
        transaction.loadPermsOnPages()), "EdEMAY0CHAT")

      val (reviewReasons: Seq[ReviewReason], _) =
        throwOrFindReviewPostReasons(page.meta, authorAndLevels, transaction)

      if (!page.role.isChat)
        throwForbidden("EsE5F0WJ2", s"Page $pageId is not a chat page; cannot insert chat message")

      val pageMemberIds = transaction.loadMessageMembers(pageId)
      if (!pageMemberIds.contains(authorId))
        throwForbidden("EsE4UGY7", "You are not a member of this chat channel")

      // Try to append to the last message, instead of creating a new one. That looks
      // better in the browser (fewer avatars & sent-by info), + we'll save disk and
      // render a little bit faster.
      val anyLastMessage = page.parts.lastPostButNotOrigPost
      val anyLastMessageSameUserRecently = anyLastMessage filter { post =>
        post.createdById == authorId &&
          transaction.now.millis - post.createdAt.getTime < LastChatMessageRecentMs
      }

      val (post, notfs) = anyLastMessageSameUserRecently match {
        case Some(lastMessage) if !lastMessage.isDeleted && lastMessage.tyype == PostType.ChatMessage =>
          appendToLastChatMessage(lastMessage, textAndHtml, byWho, spamRelReqStuff, transaction)
        case _ =>
          val (post, notfs) =
            createNewChatMessage(page, textAndHtml, byWho, spamRelReqStuff, transaction)
          // For now, let's create review tasks only for new messages, but not when appending
          // to the prev message. Should work well enough + won't be too many review tasks.
          val anyReviewTask = if (reviewReasons.isEmpty) None
          else Some(ReviewTask(
            id = transaction.nextReviewTaskId(),
            reasons = reviewReasons.to[immutable.Seq],
            createdById = SystemUserId,
            createdAt = transaction.now.toJavaDate,
            createdAtRevNr = Some(post.currentRevisionNr),
            maybeBadUserId = author.id,
            postId = Some(post.id),
            postNr = Some(post.nr)))
          anyReviewTask.foreach(transaction.upsertReviewTask)
          (post, notfs)
      }
      (post, author, notfs)
    }

    refreshPageInMemCache(pageId)

    val storePatchJson = jsonMaker.makeStorePatch(post, author, showHidden = true)
    pubSub.publish(StorePatchMessage(siteId, pageId, storePatchJson, notifications),
      byId = author.id)

    InsertPostResult(storePatchJson, post, reviewTask = None)
  }


  private def createNewChatMessage(page: PageDao, textAndHtml: TextAndHtml, who: Who,
      spamRelReqStuff: SpamRelReqStuff, transaction: SiteTransaction): (Post, Notifications) = {

    // Note: Farily similar to insertReply() a bit above. [4UYKF21]
    val authorId = who.id

    val uniqueId = transaction.nextPostId()
    val postNr = page.parts.highestReplyNr.map(_ + 1) getOrElse PageParts.FirstReplyNr
    if (!page.role.isChat)
      throwForbidden("EsE6JU04", s"Page '${page.id}' is not a chat page")

    // This is better than some database foreign key error.
    transaction.loadUser(authorId) getOrElse throwNotFound("EsE2YG8", "Bad user")

    val newPost = Post.create(
      uniqueId = uniqueId,
      pageId = page.id,
      postNr = postNr,
      parent = None,
      multireplyPostNrs = Set.empty,
      postType = PostType.ChatMessage,
      createdAt = transaction.now.toJavaDate,
      createdById = authorId,
      source = textAndHtml.text,
      htmlSanitized = textAndHtml.safeHtml,
      approvedById = Some(SystemUserId))

    // COULD find the most recent posters in the last 100 messages only, because is chat.
    val newFrequentPosterIds: Seq[UserId] =
      PageParts.findFrequentPosters(newPost +: page.parts.allPosts,
        ignoreIds = Set(page.meta.authorId, authorId))

    val oldMeta = page.meta
    val newMeta = oldMeta.copy(
      bumpedAt = page.isClosed ? oldMeta.bumpedAt | Some(transaction.now.toJavaDate),
      // Chat messages are always visible, so increment all num-replies counters.
      numRepliesVisible = oldMeta.numRepliesVisible + 1,
      numRepliesTotal = oldMeta.numRepliesTotal + 1,
      numPostsTotal = oldMeta.numPostsTotal + 1,
      //numOrigPostRepliesVisible <— leave as is — chat messages aren't orig post replies.
      lastReplyAt = Some(transaction.now.toJavaDate),
      lastReplyById = Some(authorId),
      frequentPosterIds = newFrequentPosterIds,
      version = oldMeta.version + 1)

    val uploadRefs = findUploadRefsInPost(newPost)

    SECURITY // COULD: if is new chat user, create review task to look at his/her first
    // chat messages, but only the first few.

    val auditLogEntry = AuditLogEntry(
      siteId = siteId,
      id = AuditLogEntry.UnassignedId,
      didWhat = AuditLogEntryType.NewChatMessage,
      doerId = authorId,
      doneAt = transaction.now.toJavaDate,
      browserIdData = who.browserIdData,
      pageId = Some(page.id),
      uniquePostId = Some(newPost.id),
      postNr = Some(newPost.nr),
      targetUniquePostId = None,
      targetPostNr = None,
      targetUserId = None)

    val userStats = UserStats(
      authorId,
      lastSeenAt = transaction.now,
      lastPostedAt = Some(transaction.now),
      firstChatMessageAt = Some(transaction.now),
      numChatMessagesPosted = 1)

    addUserStats(userStats)(transaction)
    transaction.insertPost(newPost)
    transaction.indexPostsSoon(newPost)
    transaction.spamCheckPostsSoon(who, spamRelReqStuff, newPost)
    transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = true)
    updatePagePopularity(page.parts, transaction)
    uploadRefs foreach { uploadRef =>
      transaction.insertUploadedFileReference(newPost.id, uploadRef, authorId)
    }
    insertAuditLogEntry(auditLogEntry, transaction)

    // generate json? load all page members?
    // send the post + json back to the caller?
    // & publish [pubsub]

    val notfs = NotificationGenerator(transaction).generateForNewPost(page, newPost)
    transaction.saveDeleteNotifications(notfs)

    (newPost, notfs)
  }


  private def appendToLastChatMessage(lastPost: Post, textAndHtml: TextAndHtml, byWho: Who,
        spamRelReqStuff: SpamRelReqStuff, transaction: SiteTransaction): (Post, Notifications) = {

    // Note: Farily similar to editPostIfAuth() just below. [2GLK572]
    val authorId = byWho.id

    dieIf(lastPost.tyype != PostType.ChatMessage, "EsE6YUW2", o"""Post id ${lastPost.id}
          is not a chat message""")

    require(lastPost.currentRevisionById == authorId, "EsE5JKU0")
    require(lastPost.currentSourcePatch.isEmpty, "EsE7YGKU2")
    require(lastPost.currentRevisionNr == FirstRevisionNr, "EsE2FWY2")
    require(lastPost.isCurrentVersionApproved, "EsE4GK7Y2")
    // The system user auto approves all chat messages and edits of chat messages. [7YKU24]
    require(lastPost.approvedById.contains(SystemUserId), "EsE4GBF3")
    require(lastPost.approvedRevisionNr.contains(FirstRevisionNr), "EsE4PKW1")
    require(lastPost.deletedAt.isEmpty, "EsE2GKY8")

    val theApprovedSource = lastPost.approvedSource.getOrDie("EsE5GYKF2")
    val theApprovedHtmlSanitized = lastPost.approvedHtmlSanitized.getOrDie("EsE2PU8")
    val newText = textEndingWithNumNewlines(theApprovedSource, 2) + textAndHtml.text
    val newHtml = textEndingWithNumNewlines(theApprovedHtmlSanitized, 2) + textAndHtml.safeHtml

    val editedPost = lastPost.copy(
      approvedSource = Some(newText),
      approvedHtmlSanitized = Some(newHtml),
      approvedAt = Some(transaction.now.toJavaDate),
      // Leave approvedById = SystemUserId and approvedRevisionNr = FirstRevisionNr unchanged.
      currentRevLastEditedAt = Some(transaction.now.toJavaDate),
      lastApprovedEditAt = Some(transaction.now.toJavaDate),
      lastApprovedEditById = Some(authorId))

    transaction.updatePost(editedPost)
    transaction.indexPostsSoon(editedPost)
    transaction.spamCheckPostsSoon(byWho, spamRelReqStuff, editedPost)
    saveDeleteUploadRefs(lastPost, editedPost = editedPost, authorId, transaction)

    val oldMeta = transaction.loadThePageMeta(lastPost.pageId)
    val newMeta = oldMeta.copy(version = oldMeta.version + 1)
    transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = true)

    // COULD create audit log entry that shows that this ip appended to the chat message.

    val notfs = NotificationGenerator(transaction).generateForEdits(lastPost, editedPost)
    transaction.saveDeleteNotifications(notfs)

    (editedPost, notfs)
  }


  def textEndingWithNumNewlines(text: String, num: Int): String = {
    val numAlready = text.takeRightWhile(_ == '\n').length
    text + "\n" * math.max(0, num - numAlready)
  }


  /** Edits the post, if authorized to edit it.
    */
  def editPostIfAuth(pageId: PageId, postNr: PostNr, who: Who, spamRelReqStuff: SpamRelReqStuff,
        newTextAndHtml: TextAndHtml) {
    val editorId = who.id

    // Note: Farily similar to appendChatMessageToLastMessage() just above. [2GLK572]

    if (newTextAndHtml.safeHtml.trim.isEmpty)
      throwBadReq("DwE4KEL7", EditController.EmptyPostErrorMessage)

    quickCheckIfSpamThenThrow(who, newTextAndHtml, spamRelReqStuff)

    readWriteTransaction { transaction =>
      val editorAndLevels = loadUserAndLevels(who, transaction)
      val editor = editorAndLevels.user
      val page = PageDao(pageId, transaction)

      val postToEdit = page.parts.postByNr(postNr) getOrElse {
        page.meta // this throws page-not-fount if the page doesn't exist
        throwNotFound("DwE404GKF2", s"Post not found, id: '$postNr'")
      }

      if (postToEdit.currentSource == newTextAndHtml.text)
        return

      dieOrThrowNoUnless(Authz.mayEditPost(
        editorAndLevels, transaction.loadGroupIds(editor),
        postToEdit, page.meta, transaction.loadAnyPrivateGroupTalkMembers(page.meta),
        inCategoriesRootLast = transaction.loadCategoryPathRootLast(page.meta.categoryId),
        permissions = transaction.loadPermsOnPages()), "EdE6JLKW2R")

      // COULD don't allow sbd else to edit until 3 mins after last edit by sbd else?
      // so won't create too many revs quickly because 2 edits.
      BUG // COULD compare version number: kills the lost update bug.

      // If we've saved an old revision already, and 1) there hasn't been any more discussion
      // in this sub thread since the current revision was started, and 2) the current revision
      // hasn't been flagged, — then don't save a new revision. It's rather uninteresting
      // to track changes, when no discussion is happening.
      // (We avoid saving unneeded revisions, to save disk.)
      val anyLastRevision = loadLastRevisionWithSource(postToEdit.id, transaction)
      def oldRevisionSavedAndNothingHappened = anyLastRevision match {
        case None => false
        case Some(_) =>
          // COULD: instead of comparing timestamps, flags and replies could explicitly clarify
          // which revision of postToEdit they concern.
          val currentRevStartMs = postToEdit.currentRevStaredAt.getTime
          val flags = transaction.loadFlagsFor(immutable.Seq(PagePostNr(pageId, postNr)))
          val anyNewFlag = flags.exists(_.flaggedAt.millis > currentRevStartMs)
          val successors = page.parts.descendantsOf(postNr)
          val anyNewComment = successors.exists(_.createdAt.getTime > currentRevStartMs)
        !anyNewComment && !anyNewFlag
      }

      // Define new field values as if we'll approve the post, but change them below (5AKW02)
      // if in fact we won't approve it.
      var editsApproved = true
      var newCurrentSourcePatch: Option[String] = None
      var newLastApprovedEditAt = Option(transaction.now.toJavaDate)
      var newLastApprovedEditById = Option(editorId)
      var newApprovedSource = Option(newTextAndHtml.text)
      var newApprovedHtmlSanitized = Option(newTextAndHtml.safeHtml)
      var newApprovedAt = Option(transaction.now.toJavaDate)

      val newApprovedById =
        if (postToEdit.tyype == PostType.ChatMessage) {
          // The system user auto approves all chat messages; always use SystemUserId for chat.
          Some(SystemUserId)  // [7YKU24]
        }
        else if (editor.isStaff) {
          Some(editor.id)
        }
        else {
          // For now, let people continue editing a post that has been approved already.
          // Later, could avoid approving, if user threat level >= mild threat,
          // or if some not-yet-created site config settings are strict?
          // Would then need to create a new revision.
          if (postToEdit.isCurrentVersionApproved) {
            Some(SystemUserId)
          }
          else {
            // Don't auto-approve these edits. (5AKW02)
            editsApproved = false
            newCurrentSourcePatch = Some(makePatch(
              from = postToEdit.approvedSource.getOrElse(""), to = newTextAndHtml.text))
            newLastApprovedEditAt = postToEdit.lastApprovedEditAt
            newLastApprovedEditById = postToEdit.lastApprovedEditById
            newApprovedSource = postToEdit.approvedSource
            newApprovedHtmlSanitized = postToEdit.approvedHtmlSanitized
            newApprovedAt = postToEdit.approvedAt
            postToEdit.approvedById
          }
        }

      val isInNinjaEditWindow = {
        val ninjaWindowMs = ninjaEditWindowMsFor(page.role)
        val ninjaEditEndMs = postToEdit.currentRevStaredAt.getTime + ninjaWindowMs
        transaction.now.millis < ninjaEditEndMs
      }

      val isNinjaEdit = {
        val sameAuthor = postToEdit.currentRevisionById == editorId
        val ninjaHardEndMs = postToEdit.currentRevStaredAt.getTime + HardMaxNinjaEditWindowMs
        val isInHardWindow = transaction.now.millis < ninjaHardEndMs
        sameAuthor && isInHardWindow && (isInNinjaEditWindow || oldRevisionSavedAndNothingHappened)
      }

      val (newRevision: Option[PostRevision], newStartedAt, newRevisionNr, newPrevRevNr) =
        if (isNinjaEdit) {
          (None, postToEdit.currentRevStaredAt, postToEdit.currentRevisionNr,
            postToEdit.previousRevisionNr)
        }
        else {
          val revision = PostRevision.createFor(postToEdit, previousRevision = anyLastRevision)
          (Some(revision), transaction.now.toJavaDate, postToEdit.currentRevisionNr + 1,
            Some(postToEdit.currentRevisionNr))
        }

      val newApprovedRevNr = editsApproved ? Option(newRevisionNr) | postToEdit.approvedRevisionNr

      // COULD send current version from browser to server, reject edits if != oldPost.currentVersion
      // to stop the lost update problem.

      var editedPost = postToEdit.copy(
        currentRevStaredAt = newStartedAt,
        currentRevLastEditedAt = Some(transaction.now.toJavaDate),
        currentRevisionById = editorId,
        currentSourcePatch = newCurrentSourcePatch,
        currentRevisionNr = newRevisionNr,
        previousRevisionNr = newPrevRevNr,
        lastApprovedEditAt = newLastApprovedEditAt,
        lastApprovedEditById = newLastApprovedEditById,
        approvedSource = newApprovedSource,
        approvedHtmlSanitized = newApprovedHtmlSanitized,
        approvedAt = newApprovedAt,
        approvedById = newApprovedById,
        approvedRevisionNr = newApprovedRevNr)

      if (editorId != editedPost.createdById) {
        editedPost = editedPost.copy(numDistinctEditors = 2)  // for now
      }

      // If we're editing an about-category-post == a category description, update the category.
      val editsAboutCategoryPost = page.role == PageRole.AboutCategory && editedPost.isOrigPost
      val anyEditedCategory =
        if (!editsAboutCategoryPost || !editsApproved) {
          if (editsAboutCategoryPost && !editsApproved) {
            // Currently needn't fix this? Only staff can edit these posts, right now.
            unimplemented("Updating a category later when its about-page orig post gets approved",
              "EdE2WK7AC")
          }
          None
        }
        else {
          val category = transaction.loadCategory(page.meta.categoryId getOrDie "DwE2PKF0")
                .getOrDie("EdE8ULK4E")
          val excerpt = JsonMaker.htmlToExcerpt(
            newTextAndHtml.safeHtml, Category.DescriptionExcerptLength,
            firstParagraphOnly = true)
          Some(category.copy(description = Some(excerpt.text)))
        }

      val postRecentlyCreated = transaction.now.millis - postToEdit.createdAt.getTime <=
          AllSettings.PostRecentlyCreatedLimitMs

      val reviewTask: Option[ReviewTask] =
        if (postRecentlyCreated || editor.isStaff) {
          // Need not review a recently created post: it's new and the edits likely
          // happened before other people read it, so they'll notice any weird things
          // later when they read it, and can flag it. This is not totally safe,
          // but better than forcing the staff to review all edits? (They'd just
          // get bored and stop reviewing.)
          // The way to do this in a really safe manner: Create a invisible inactive post-edited
          // review task, which gets activated & shown after x hours if too few people have read
          // the post. But if many has seen the post, the review task instead gets deleted.
          None
        }
        else if (!postToEdit.isSomeVersionApproved && !editedPost.isSomeVersionApproved) {
          // Review task should already have been created.
          None
        }
        else {
          // Later, COULD specify editor id instead, as ReviewTask.maybeBadUserId [6KW02QS]
          Some(makeReviewTask(SystemUserId, editedPost, immutable.Seq(ReviewReason.LateEdit),
            transaction))
        }

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.EditPost,
        doerId = editorId,
        doneAt = transaction.now.toJavaDate,
        browserIdData = who.browserIdData,
        pageId = Some(pageId),
        uniquePostId = Some(postToEdit.id),
        postNr = Some(postNr),
        targetUserId = Some(postToEdit.createdById))

      transaction.updatePost(editedPost)
      transaction.indexPostsSoon(editedPost)
      transaction.spamCheckPostsSoon(who, spamRelReqStuff, editedPost)
      newRevision.foreach(transaction.insertPostRevision)
      saveDeleteUploadRefs(postToEdit, editedPost = editedPost, editorId, transaction)

      insertAuditLogEntry(auditLogEntry, transaction)
      anyEditedCategory.foreach(transaction.updateCategoryMarkSectionPageStale)
      reviewTask.foreach(transaction.upsertReviewTask)

      if (!postToEdit.isSomeVersionApproved && editedPost.isSomeVersionApproved) {
        unimplemented("Updating visible post counts when post approved via an edit", "DwE5WE28")
      }

      if (editedPost.isCurrentVersionApproved) {
        val notfs = NotificationGenerator(transaction).generateForEdits(postToEdit, editedPost)
        transaction.saveDeleteNotifications(notfs)
      }

      val oldMeta = page.meta
      var newMeta = oldMeta.copy(version = oldMeta.version + 1)
      var makesSectionPageHtmlStale = false
      // Bump the page, if the article / original post was edited.
      // (This is how Discourse works and people seems to like it. However,
      // COULD add a don't-bump option for minor edits.)
      if (postNr == PageParts.BodyNr && editedPost.isCurrentVersionApproved && !page.isClosed) {
        newMeta = newMeta.copy(bumpedAt = Some(transaction.now.toJavaDate))
        makesSectionPageHtmlStale = true
      }
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, makesSectionPageHtmlStale)
    }

    refreshPageInMemCache(pageId)
  }


  private def saveDeleteUploadRefs(postToEdit: Post, editedPost: Post, editorId: UserId,
        transaction: SiteTransaction) {
    // Use findUploadRefsInPost (not ...InText) so we'll find refs both in the hereafter
    // 1) approved version of the post, and 2) the current possibly unapproved version.
    // Because if any of the approved or the current version links to an uploaded file,
    // we should keep the file.
    val currentUploadRefs = findUploadRefsInPost(editedPost)
    val oldUploadRefs = transaction.loadUploadedFileReferences(postToEdit.id)
    val uploadRefsAdded = currentUploadRefs -- oldUploadRefs
    val uploadRefsRemoved = oldUploadRefs -- currentUploadRefs

    uploadRefsAdded foreach { hashPathSuffix =>
      transaction.insertUploadedFileReference(postToEdit.id, hashPathSuffix, editorId)
    }

    uploadRefsRemoved foreach { hashPathSuffix =>
      val gone = transaction.deleteUploadedFileReference(postToEdit.id, hashPathSuffix)
      if (!gone) {
        p.Logger.warn(o"""Didn't delete this uploaded file ref: $hashPathSuffix, post id:
            ${postToEdit.id} [DwE7UMF2]""")
      }
    }
  }


  def loadSomeRevisionsRecentFirst(postId: PostId, revisionNr: Int, atLeast: Int,
        userId: Option[UserId]): (Seq[PostRevision], Map[UserId, User]) = {
    val revisionsRecentFirst = mutable.ArrayStack[PostRevision]()
    var usersById: Map[UserId, User] = null
    readOnlyTransaction { transaction =>
      val post = transaction.loadThePost(postId)
      val page = PageDao(post.pageId, transaction)
      val user = userId.flatMap(transaction.loadUser)

      throwIfMayNotSeePost(post, user)(transaction)

      loadSomeRevisionsWithSourceImpl(postId, revisionNr, revisionsRecentFirst,
        atLeast, transaction)
      if (revisionNr == PostRevision.LastRevisionMagicNr) {
        val postNow = transaction.loadThePost(postId)
        val currentRevision = PostRevision.createFor(postNow, revisionsRecentFirst.headOption)
          .copy(fullSource = Some(postNow.currentSource))
        revisionsRecentFirst.push(currentRevision)
      }
      val userIds = mutable.HashSet[UserId]()
      revisionsRecentFirst foreach { revision =>
        userIds add revision.composedById
        revision.approvedById foreach userIds.add
        revision.hiddenById foreach userIds.add
      }
      usersById = transaction.loadUsersAsMap(userIds)
    }
    (revisionsRecentFirst.toSeq, usersById)
  }


  private def loadLastRevisionWithSource(postId: PostId, transaction: SiteTransaction)
        : Option[PostRevision] = {
    val revisionsRecentFirst = mutable.ArrayStack[PostRevision]()
    loadSomeRevisionsWithSourceImpl(postId, PostRevision.LastRevisionMagicNr,
      revisionsRecentFirst, atLeast = 1, transaction)
    revisionsRecentFirst.headOption
  }


  private def loadSomeRevisionsWithSourceImpl(postId: PostId, revisionNr: Int,
        revisionsRecentFirst: mutable.ArrayStack[PostRevision], atLeast: Int,
        transaction: SiteTransaction) {
    transaction.loadPostRevision(postId, revisionNr) foreach { revision =>
      loadRevisionsFillInSource(revision, revisionsRecentFirst, atLeast, transaction)
    }
  }


  private def loadRevisionsFillInSource(revision: PostRevision,
        revisionsRecentFirstWithSource: mutable.ArrayStack[PostRevision],
        atLeast: Int, transaction: SiteTransaction) {
    if (revision.fullSource.isDefined && (atLeast <= 1 || revision.previousNr.isEmpty)) {
      revisionsRecentFirstWithSource.push(revision)
      return
    }

    val previousRevisionNr = revision.previousNr.getOrDie(
      "DwE08SKF3", o"""In site $siteId, post ${revision.postId} revision ${revision.revisionNr}
          has neither full source nor any previous revision nr""")

    val previousRevision =
      transaction.loadPostRevision(revision.postId, previousRevisionNr).getOrDie(
        "DwE5GLK2", o"""In site $siteId, post ${revision.postId} revision $previousRevisionNr
            is missing""")

    loadRevisionsFillInSource(previousRevision, revisionsRecentFirstWithSource,
      atLeast - 1, transaction)

    val prevRevWithSource = revisionsRecentFirstWithSource.headOption getOrDie "DwE85UF2"
    val revisionWithSource =
      if (revision.fullSource.isDefined) revision
      else revision.copyAndPatchSourceFrom(prevRevWithSource)
    revisionsRecentFirstWithSource.push(revisionWithSource)
  }


  def editPostSettings(postId: PostId, branchSideways: Option[Byte], me: Who): JsValue = {
    val (post, patch) = readWriteTransaction { transaction =>
      val postBefore = transaction.loadPostsByUniqueId(Seq(postId)).headOption.getOrElse({
        throwNotFound("EsE5KJ8W2", s"Post not found: $postId")
      })._2
      val postAfter = postBefore.copy(branchSideways = branchSideways)

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.ChangePostSettings,
        doerId = me.id,
        doneAt = transaction.now.toJavaDate,
        browserIdData = me.browserIdData,
        pageId = Some(postBefore.pageId),
        uniquePostId = Some(postBefore.id),
        postNr = Some(postBefore.nr),
        targetUserId = Some(postBefore.createdById))

      val oldMeta = transaction.loadThePageMeta(postAfter.pageId)
      val newMeta = oldMeta.copy(version = oldMeta.version + 1)

      // (Don't reindex. For now, don't send any notifications (since currently just toggling
      // branch-sideways))
      transaction.updatePost(postAfter)
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = false)
      insertAuditLogEntry(auditLogEntry, transaction)

      COULD_OPTIMIZE // try not to load the whole page in makeStorePatch2
      (postAfter, jsonMaker.makeStorePatch2(postId, postAfter.pageId,
          appVersion = globals.applicationVersion, transaction))
    }
    refreshPageInMemCache(post.pageId)
    patch
  }


  def changePostType(pageId: PageId, postNr: PostNr, newType: PostType,
        changerId: UserId, browserIdData: BrowserIdData) {
    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val postBefore = page.parts.thePostByNr(postNr)
      val Seq(author, changer) = transaction.loadTheUsers(postBefore.createdById, changerId)
      throwIfMayNotSeePage(page, Some(changer))(transaction)

      val postAfter = postBefore.copy(tyype = newType)

      // Test if the changer is allowed to change the post type in this way.
      if (changer.isStaff) {
        (postBefore.tyype, postAfter.tyype) match {
          case (before, after)
            if before == PostType.Normal && after.isWiki => // Fine, staff wikifies post.
          case (before, after)
            if before.isWiki && after == PostType.Normal => // Fine, staff removes wiki status.
          case (before, after) =>
            throwForbidden("DwE7KFE2", s"Cannot change post type from $before to $after")
        }
      }
      else {
        // All normal users may do is to remove wiki status of their own posts.
        if (postBefore.isWiki && postAfter.tyype == PostType.Normal) {
          if (changer.id != author.id)
            throwForbidden("DwE5KGPF2", o"""You are not the author and not staff,
                so you cannot remove the Wiki status of this post""")
        }
        else {
            throwForbidden("DwE4KXB2", s"""Cannot change post type from
                ${postBefore.tyype} to ${postAfter.tyype}""")
        }
      }

      val auditLogEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.ChangePostSettings,
        doerId = changerId,
        doneAt = transaction.now.toJavaDate,
        browserIdData = browserIdData,
        pageId = Some(pageId),
        uniquePostId = Some(postBefore.id),
        postNr = Some(postNr),
        targetUserId = Some(postBefore.createdById))

      val oldMeta = page.meta
      val newMeta = oldMeta.copy(version = oldMeta.version + 1)

      // (Don't reindex)
      transaction.updatePost(postAfter)
      transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale = false)
      insertAuditLogEntry(auditLogEntry, transaction)
      // COULD generate some notification? E.g. "Your post was made wiki-editable."
    }

    refreshPageInMemCache(pageId)
  }


  def changePostStatus(postNr: PostNr, pageId: PageId, action: PostStatusAction, userId: UserId) {
    readWriteTransaction(changePostStatusImpl(postNr, pageId = pageId, action, userId = userId, _))
  }


  def changePostStatusImpl(postNr: PostNr, pageId: PageId, action: PostStatusAction,
        userId: UserId, transaction: SiteTransaction) {
    import com.debiki.core.{PostStatusAction => PSA}

    val page = PageDao(pageId, transaction)
    val user = transaction.loadUser(userId) getOrElse throwForbidden("DwE3KFW2", "Bad user id")
    throwIfMayNotSeePage(page, Some(user))(transaction)

    val postBefore = page.parts.thePostByNr(postNr)

    // Authorization.
    if (!user.isStaff) {
      if (postBefore.createdById != userId)
        throwForbidden("DwE0PK24", "You may not modify that post, it's not yours")

      if (!action.isInstanceOf[PSA.DeletePost] && action != PSA.CollapsePost)
        throwForbidden("DwE5JKF7", "You may not modify the whole tree")
    }

    val isChangingDeletePostToDeleteTree =
      postBefore.deletedStatus.onlyThisDeleted && action == PSA.DeleteTree
    if (postBefore.isDeleted && !isChangingDeletePostToDeleteTree)
      throwForbidden("DwE5GUK5", "This post has already been deleted")

    var numVisibleRepliesGone = 0
    var numVisibleRepliesBack = 0
    var numOrigPostVisibleRepliesGone = 0
    var numOrigPostVisibleRepliesBack = 0

    def updateNumVisible(postBefore: Post, postAfter: Post) {
      if (!postBefore.isReply)
        return
      if (postBefore.isVisible && !postAfter.isVisible) {
        dieIf(numVisibleRepliesBack > 0, "EdE6PK4W0")
        numVisibleRepliesGone += 1
        if (postBefore.isOrigPostReply) {
          numOrigPostVisibleRepliesGone += 1
        }
      }
      if (!postBefore.isVisible && postAfter.isVisible) {
        dieIf(numVisibleRepliesGone > 0, "EdE7BST2Z")
        numVisibleRepliesBack += 1
        if (postBefore.isOrigPostReply) {
          numOrigPostVisibleRepliesBack += 1
        }
      }
    }

    // Update the directly affected post.
    val postAfter = action match {
      case PSA.HidePost =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, bodyHidden = true)
      case PSA.UnhidePost =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, bodyUnhidden = true)
      case PSA.CloseTree =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, treeClosed = true)
      case PSA.CollapsePost =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, postCollapsed = true)
      case PSA.CollapseTree =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, treeCollapsed = true)
      case PSA.DeletePost(clearFlags) =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, postDeleted = true)
      case PSA.DeleteTree =>
        postBefore.copyWithNewStatus(transaction.now.toJavaDate, userId, treeDeleted = true)
    }

    updateNumVisible(postBefore, postAfter = postAfter)
    SHOULD // delete any review tasks.

    transaction.updatePost(postAfter)
    if (postBefore.isDeleted != postAfter.isDeleted) {
      transaction.indexPostsSoon(postAfter)
    }

    // Update any indirectly affected posts, e.g. subsequent comments in the same
    // thread that are being deleted recursively.
    for (successor <- page.parts.descendantsOf(postNr)) {
      val anyUpdatedSuccessor: Option[Post] = action match {
        case PSA.CloseTree =>
          if (successor.closedStatus.areAncestorsClosed) None
          else Some(successor.copyWithNewStatus(
            transaction.now.toJavaDate, userId, ancestorsClosed = true))
        case PSA.CollapsePost =>
          None
        case PSA.CollapseTree =>
          if (successor.collapsedStatus.areAncestorsCollapsed) None
          else Some(successor.copyWithNewStatus(
            transaction.now.toJavaDate, userId, ancestorsCollapsed = true))
        case PSA.DeletePost(clearFlags) =>
          None
        case PSA.DeleteTree =>
          if (successor.deletedStatus.areAncestorsDeleted) None
          else Some(successor.copyWithNewStatus(
            transaction.now.toJavaDate, userId, ancestorsDeleted = true))
        case x =>
          die("DwE8FMU3", "PostAction not implemented: " + x)
      }

      var postsToReindex = Vector[Post]()
      anyUpdatedSuccessor foreach { updatedSuccessor =>
        updateNumVisible(postBefore = successor, postAfter = updatedSuccessor)
        transaction.updatePost(updatedSuccessor)
        if (successor.isDeleted != updatedSuccessor.isDeleted) {
          postsToReindex :+= updatedSuccessor
        }
      }
      transaction.indexPostsSoon(postsToReindex: _*)
    }

    val oldMeta = page.meta
    var newMeta = oldMeta.copy(version = oldMeta.version + 1)
    var markSectionPageStale = false
    // COULD update database to fix this. (Previously, chat pages didn't count num-chat-messages.)
    val isChatWithWrongReplyCount =
      page.role.isChat && oldMeta.numRepliesVisible == 0 && numVisibleRepliesGone > 0
    val numVisibleRepliesChanged = numVisibleRepliesGone > 0 || numVisibleRepliesBack > 0

    if (numVisibleRepliesChanged && !isChatWithWrongReplyCount) {
      newMeta = newMeta.copy(
        numRepliesVisible =
            oldMeta.numRepliesVisible + numVisibleRepliesBack - numVisibleRepliesGone,
        numOrigPostRepliesVisible =
          // For now: use max() because the db field was just added so some counts are off.
          math.max(0, oldMeta.numOrigPostRepliesVisible +
              numOrigPostVisibleRepliesBack - numOrigPostVisibleRepliesGone))
      markSectionPageStale = true
      updatePagePopularity(page.parts, transaction)
    }

    transaction.updatePageMeta(newMeta, oldMeta = oldMeta, markSectionPageStale)

      // In the future: if is a forum topic, and we're restoring the OP, then bump the topic.


    refreshPageInMemCache(pageId)
  }


  def approvePostImpl(pageId: PageId, postNr: PostNr, approverId: UserId,
        transaction: SiteTransaction) {

    val page = PageDao(pageId, transaction)
    val pageMeta = page.meta
    val postBefore = page.parts.thePostByNr(postNr)
    if (postBefore.isCurrentVersionApproved)
      throwForbidden("DwE4GYUR2", s"Post nr ${postBefore.nr} already approved")

    val approver = transaction.loadTheUser(approverId)

    // For now. Later, let core members approve posts too.
    if (!approver.isStaff)
      throwForbidden("EsE5GYK02", "You're not staff so you cannot approve posts")

    // ------ The post

    val renderSettings = PostRendererSettings(pageMeta.pageRole, thePubSiteId())
    val approvedHtmlSanitized = context.postRenderer.renderAndSanitize(postBefore, renderSettings,
      IfCached.Die("TyE2BKYUF4"))

    // Later: update lastApprovedEditAt, lastApprovedEditById and numDistinctEditors too,
    // or remove them.
    val postAfter = postBefore.copy(
      safeRevisionNr =
        approver.isHuman ? Option(postBefore.currentRevisionNr) | postBefore.safeRevisionNr,
      approvedRevisionNr = Some(postBefore.currentRevisionNr),
      approvedAt = Some(transaction.now.toJavaDate),
      approvedById = Some(approverId),
      approvedSource = Some(postBefore.currentSource),
      approvedHtmlSanitized = Some(approvedHtmlSanitized),
      currentSourcePatch = None,
      // SPAM RACE COULD unhide only if rev nr that got hidden <= rev that was reviewed. [6GKC3U]
      bodyHiddenAt = None,
      bodyHiddenById = None,
      bodyHiddenReason = None)
    transaction.updatePost(postAfter)
    transaction.indexPostsSoon(postAfter)

    SHOULD // delete any review tasks.

    // ------ The page

    val isApprovingPageTitle = postNr == PageParts.TitleNr
    val isApprovingPageBody = postNr == PageParts.BodyNr
    val isApprovingNewPost = postBefore.approvedRevisionNr.isEmpty

    var newMeta = pageMeta.copy(version = pageMeta.version + 1)

    // If we're approving the page, unhide it.
    BUG // rather harmless: If page hidden because of flags, then if new reply approved,
    // the page should be shown, because now there's a visible reply. But it'll remain hidden.
    val newHiddenAt =
      if (isApprovingPageBody && isApprovingNewPost) {
        UNTESTED
        None
      }
      else newMeta.hiddenAt

    // Bump page and update reply counts if a new post was approved and became visible,
    // or if the original post was edited.
    var makesSectionPageHtmlStale = false
    if (isApprovingNewPost || isApprovingPageBody) {
      val (numNewReplies, numNewOrigPostReplies, newLastReplyAt, newLastReplyById) =
        if (isApprovingNewPost && postAfter.isReply)
          (1, postAfter.isOrigPostReply ? 1 | 0,
            Some(transaction.now.toJavaDate), Some(postAfter.createdById))
        else
          (0, 0, pageMeta.lastReplyAt, pageMeta.lastReplyById)

      newMeta = newMeta.copy(
        numRepliesVisible = pageMeta.numRepliesVisible + numNewReplies,
        numOrigPostRepliesVisible = pageMeta.numOrigPostRepliesVisible + numNewOrigPostReplies,
        lastReplyAt = newLastReplyAt,
        lastReplyById = newLastReplyById,
        hiddenAt = newHiddenAt,
        bumpedAt = pageMeta.isClosed ? pageMeta.bumpedAt | Some(transaction.now.toJavaDate))
      makesSectionPageHtmlStale = true
    }
    transaction.updatePageMeta(newMeta, oldMeta = pageMeta, makesSectionPageHtmlStale)
    updatePagePopularity(page.parts, transaction)

    // ------ Notifications

    val notifications =
      if (isApprovingPageTitle && isApprovingNewPost) {
        // Notifications will be generated for the page body, that should be enough?
        Notifications.None
      }
      else if (isApprovingNewPost) {
        NotificationGenerator(transaction).generateForNewPost(page, postAfter)
      }
      else {
        NotificationGenerator(transaction).generateForEdits(postBefore, postAfter)
      }
    transaction.saveDeleteNotifications(notifications)

    refreshPagesInAnyCache(Set[PageId](pageId))
  }


  def autoApprovePendingEarlyPosts(pageId: PageId, posts: Iterable[Post])(
        transaction: SiteTransaction) {

    if (posts.isEmpty) return
    require(posts.forall(_.pageId == pageId), "EdE2AX5N6")

    val page = PageDao(pageId, transaction)
    val pageMeta = page.meta

    var numNewVisibleReplies = 0
    var numNewVisibleOpReplies = 0

    for (post <- posts) {
      dieIf(post.isSomeVersionApproved, "EsE6YKP2", s"Post ${post.pagePostId} already approved")

      numNewVisibleReplies += post.isReply ? 1 | 0
      numNewVisibleOpReplies += post.isOrigPostReply ? 1 | 0

      // ----- A post

      val renderSettings = PostRendererSettings(pageMeta.pageRole, thePubSiteId())
      val approvedHtmlSanitized = context.postRenderer.renderAndSanitize(post, renderSettings,
        IfCached.Die("TyE2PKL99"))

      // Don't need to update lastApprovedEditAt, because this post has been invisible until now.
      // Don't set safeRevisionNr, because this approval hasn't been reviewed by a human.
      val postAfter = post.copy(
        approvedRevisionNr = Some(post.currentRevisionNr),
        approvedAt = Some(transaction.now.toJavaDate),
        approvedById = Some(SystemUserId),
        approvedSource = Some(post.currentSource),
        approvedHtmlSanitized = Some(approvedHtmlSanitized),
        currentSourcePatch = None)

      transaction.updatePost(postAfter)
      transaction.indexPostsSoon(postAfter)

      // ------ Notifications

      if (!post.isTitle) {
        val notfs = NotificationGenerator(transaction).generateForNewPost(page, postAfter)
        transaction.saveDeleteNotifications(notfs)
      }
    }

    // ----- The page

    // Unhide the page, if is hidden because the orig post hasn't been approved until now.
    val isNewPage = posts.exists(_.isOrigPost) && posts.exists(_.isTitle)
    val newHiddenAt = if (isNewPage) None else pageMeta.hiddenAt

    val thereAreNoReplies = isNewPage && posts.size == 2  // title + orig-post = 2

    val (newLastReplyAt, newLastReplyById) =
      if (thereAreNoReplies) {
        dieIf(pageMeta.lastReplyAt.isDefined, "EdE2KF80P", s"Page id $pageId")
        (None, None)
      }
      else {
        val newLastReply = Some(posts.maxBy(_.createdAt.getTime))
        (Some(transaction.now.toJavaDate), newLastReply.map(_.createdById))
      }

    val newMeta = pageMeta.copy(
      numRepliesVisible = pageMeta.numRepliesVisible + numNewVisibleReplies,
      numOrigPostRepliesVisible = pageMeta.numOrigPostRepliesVisible + numNewVisibleOpReplies,
      lastReplyAt = newLastReplyAt,
      lastReplyById = newLastReplyById,
      bumpedAt = pageMeta.isClosed ? pageMeta.bumpedAt | Some(transaction.now.toJavaDate),
      hiddenAt = newHiddenAt,
      version = pageMeta.version + 1)

    transaction.updatePageMeta(newMeta, oldMeta = pageMeta, markSectionPageStale = true)
    updatePagePopularity(page.parts, transaction)
  }


  def deletePost(pageId: PageId, postNr: PostNr, deletedById: UserId,
        browserIdData: BrowserIdData) {
    readWriteTransaction(deletePostImpl(
      pageId, postNr = postNr, deletedById = deletedById, browserIdData, _))
  }


  def deletePostImpl(pageId: PageId, postNr: PostNr, deletedById: UserId,
        browserIdData: BrowserIdData, transaction: SiteTransaction) {
    changePostStatusImpl(pageId = pageId, postNr = postNr,
      action = PostStatusAction.DeletePost(clearFlags = false), userId = deletedById,
      transaction = transaction)
  }


  def deleteVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType, voterId: UserId) {
    readWriteTransaction { tx =>
      val post = tx.loadThePost(pageId, postNr = postNr)
      val voter = tx.loadTheUser(voterId)
      throwIfMayNotSeePost(post, Some(voter))(tx)

      tx.deleteVote(pageId, postNr = postNr, voteType, voterId = voterId)
      updateVoteCounts(PagePartsDao(pageId, tx), post, tx)
      addUserStats(UserStats(post.createdById, numLikesReceived = -1, mayBeNegative = true))(tx)
      addUserStats(UserStats(voterId, numLikesGiven = -1, mayBeNegative = true))(tx)

      /* SECURITY vote-FRAUD SHOULD delete by cookie too, like I did before:
      var numRowsDeleted = 0
      if ((userIdData.anyGuestId.isDefined && userIdData.userId != UnknownUser.Id) ||
        userIdData.anyRoleId.isDefined) {
        numRowsDeleted = deleteVoteByUserId()
      }
      if (numRowsDeleted == 0 && userIdData.browserIdCookie.isDefined) {
        numRowsDeleted = deleteVoteByCookie()
      }
      if (numRowsDeleted > 1) {
        assErr("DwE8GCH0", o"""Too many votes deleted, page `$pageId' post `$postId',
          user: $userIdData, vote type: $voteType""")
      }
      */
    }
    refreshPageInMemCache(pageId)
  }


  def ifAuthAddVote(pageId: PageId, postNr: PostNr, voteType: PostVoteType,
        voterId: UserId, voterIp: String, postNrsRead: Set[PostNr]) {
    readWriteTransaction { transaction =>
      val page = PageDao(pageId, transaction)
      val voter = transaction.loadTheUser(voterId)
      SECURITY // minor. Should be if-may-not-see-*post*. And should do a pre-check in VoteController.
      throwIfMayNotSeePage(page, Some(voter))(transaction)

      val post = page.parts.thePostByNr(postNr)

      if (voteType == PostVoteType.Bury && !voter.isStaffOrFullMember &&  // [7UKDR10]
          page.meta.authorId != voterId)
        throwForbidden("DwE2WU74", "Only staff, full members and the page author may Bury-vote")

      if (voteType == PostVoteType.Unwanted && !voter.isStaffOrCoreMember)  // [4DKWV9J2]
        throwForbidden("DwE5JUK0", "Only staff and core members may Unwanted-vote")

      if (voteType == PostVoteType.Like) {
        if (post.createdById == voterId)
          throwForbidden("DwE84QM0", "Cannot like own post")
      }

      try {
        transaction.insertVote(post.id, pageId, postNr, voteType, voterId = voterId)
      }
      catch {
        case DbDao.DuplicateVoteException =>
          throwForbidden("Dw403BKW2", "You have already voted")
      }

      // Update post read stats.
      val postsToMarkAsRead =
        if (voteType == PostVoteType.Like) {
          // Upvoting a post shouldn't affect its ancestors, because they're on the
          // path to the interesting post so they are a bit useful/interesting. However
          // do mark all earlier siblings as read since they weren't upvoted (this time).
          val ancestorNrs = page.parts.ancestorsOf(postNr).map(_.nr)
          postNrsRead -- ancestorNrs.toSet
        }
        else {
          // The post got a non-like vote: wrong, bury or unwanted.
          // This should result in only the downvoted post
          // being marked as read, because a post *not* being downvoted shouldn't
          // give that post worse rating. (Remember that the rating of a post is
          // roughly the number of Like votes / num-times-it's-been-read.)
          Set(postNr)
        }

      transaction.updatePostsReadStats(pageId, postsToMarkAsRead, readById = voterId,
        readFromIp = voterIp)
      updateVoteCounts(page.parts, post, transaction)
      addUserStats(UserStats(post.createdById, numLikesReceived = 1))(transaction)
      addUserStats(UserStats(voterId, numLikesGiven = 1))(transaction)
    }
    refreshPageInMemCache(pageId)
  }


  def movePostIfAuth(whichPost: PagePostId, newParent: PagePostNr, moverId: UserId,
        browserIdData: BrowserIdData): (Post, JsValue) = {

    if (newParent.postNr == PageParts.TitleNr)
      throwForbidden("EsE4YKJ8_", "Cannot place a post below the title")

    val now = globals.now()

    val (postAfter, storePatch) = readWriteTransaction { transaction =>
      val mover = transaction.loadTheMember(moverId)
      if (!mover.isStaff)
        throwForbidden("EsE6YKG2_", "Only staff may move posts")

      val postToMove = transaction.loadThePost(whichPost.postId)
      if (postToMove.nr == PageParts.TitleNr || postToMove.nr == PageParts.BodyNr)
        throwForbidden("EsE7YKG25_", "Cannot move page title or body")

      val newParentPost = transaction.loadPost(newParent) getOrElse throwForbidden(
        "EsE7YKG42_", "New parent post not found")

      dieIf(postToMove.collapsedStatus.isCollapsed, "EsE5KGV4", "Unimpl")
      dieIf(postToMove.closedStatus.isClosed, "EsE9GKY03", "Unimpl")
      dieIf(postToMove.deletedStatus.isDeleted, "EsE4PKW12", "Unimpl")
      dieIf(newParentPost.collapsedStatus.isCollapsed, "EsE7YKG32", "Unimpl")
      dieIf(newParentPost.closedStatus.isClosed, "EsE2GLK83", "Unimpl")
      dieIf(newParentPost.deletedStatus.isDeleted, "EsE8KFG1", "Unimpl")

      val fromPage = PageDao(postToMove.pageId, transaction)
      val toPage = PageDao(newParent.pageId, transaction)

      // Don't create cycles.
      if (newParentPost.pageId == postToMove.pageId) {
        val ancestorsOfNewParent = fromPage.parts.ancestorsOf(newParentPost.nr)
        if (ancestorsOfNewParent.exists(_.id == postToMove.id))
          throwForbidden("EsE7KCCL_", o"""Cannot move a post to after one of its descendants
              — doing that, would create a cycle""")
      }

      val moveTreeAuditEntry = AuditLogEntry(
        siteId = siteId,
        id = AuditLogEntry.UnassignedId,
        didWhat = AuditLogEntryType.MovePost,
        doerId = moverId,
        doneAt = now.toJavaDate,
        browserIdData = browserIdData,
        pageId = Some(postToMove.pageId),
        uniquePostId = Some(postToMove.id),
        postNr = Some(postToMove.nr),
        targetPageId = Some(newParentPost.pageId),
        targetUniquePostId = Some(newParentPost.id),
        targetPostNr = Some(newParentPost.nr))

      val postAfter =
        if (postToMove.pageId == newParentPost.pageId) {
          val postAfter = postToMove.copy(parentNr = Some(newParentPost.nr))
          transaction.updatePost(postAfter)
          // (Need not reindex.)
          transaction.insertAuditLogEntry(moveTreeAuditEntry)
          postAfter
        }
        else {
          transaction.deferConstraints()
          transaction.startAuditLogBatch()

          val descendants = fromPage.parts.descendantsOf(postToMove.nr)
          val newNrsMap = mutable.HashMap[PostNr, PostNr]()
          val firstFreePostNr = toPage.parts.highestReplyNr.map(_ + 1) getOrElse FirstReplyNr
          var nextPostNr = firstFreePostNr
          newNrsMap.put(postToMove.nr, nextPostNr)
          for (descendant <- descendants) {
            nextPostNr += 1
            newNrsMap.put(descendant.nr, nextPostNr)
          }

          val postAfter = postToMove.copy(
            pageId = newParentPost.pageId,
            nr = firstFreePostNr,
            parentNr = Some(newParentPost.nr))

          var postsAfter = ArrayBuffer[Post](postAfter)
          var auditEntries = ArrayBuffer[AuditLogEntry](moveTreeAuditEntry)

          descendants foreach { descendant =>
            val descendantAfter = descendant.copy(
              pageId = toPage.id,
              nr = newNrsMap.get(descendant.nr) getOrDie "EsE7YKL32",
              parentNr = Some(newNrsMap.get(
                descendant.parentNr getOrDie "EsE8YKHF2") getOrDie "EsE2PU79"))
            postsAfter += descendantAfter
            auditEntries += AuditLogEntry(
              siteId = siteId,
              id = AuditLogEntry.UnassignedId,
              didWhat = AuditLogEntryType.MovePost,
              doerId = moverId,
              doneAt = now.toJavaDate,
              browserIdData = browserIdData,
              pageId = Some(descendant.pageId),
              uniquePostId = Some(descendant.id),
              postNr = Some(descendant.nr),
              targetPageId = Some(descendantAfter.pageId))
              // (leave target post blank — we didn't place decendantAfter at any
              // particular post on the target page)
          }

          postsAfter foreach transaction.updatePost
          transaction.indexPostsSoon(postsAfter: _*)
          auditEntries foreach transaction.insertAuditLogEntry
          transaction.movePostsReadStats(fromPage.id, toPage.id, Map(newNrsMap.toSeq: _*))
          // Mark both fromPage and toPage sections as stale, in case they're different forums.
          refreshPageMetaBumpVersion(fromPage.id, markSectionPageStale = true, transaction)
          refreshPageMetaBumpVersion(toPage.id, markSectionPageStale = true, transaction)

          postAfter
        }

      val notfs = NotificationGenerator(transaction).generateForNewPost(
        toPage, postAfter, skipMentions = true)
      SHOULD // transaction.saveDeleteNotifications(notfs) — but would cause unique key errors

      val patch = jsonMaker.makeStorePatch2(postAfter.id, toPage.id,
        appVersion = globals.applicationVersion, transaction)
      (postAfter, patch)
    }

    refreshPageInMemCache(newParent.pageId)
    (postAfter, storePatch)
  }


  def loadThingsToReview(): ThingsToReview = {
    readOnlyTransaction { transaction =>
      val posts = transaction.loadPostsToReview()
      val pageMetas = transaction.loadPageMetas(posts.map(_.pageId))
      val flags = transaction.loadFlagsFor(posts.map(_.pagePostNr))
      val userIds = mutable.HashSet[UserId]()
      userIds ++= posts.map(_.createdById)
      userIds ++= posts.map(_.currentRevisionById)
      userIds ++= flags.map(_.flaggerId)
      val users = transaction.loadUsers(userIds.toSeq)
      ThingsToReview(posts, pageMetas, users, flags)
    }
  }


  /** Returns all posts hidden as a result of this flag — which might be many, because
    * the flag might result in the computer believing the user is Bad, and hide all hens posts.
    */
  def flagPost(pageId: PageId, postNr: PostNr, flagType: PostFlagType, flaggerId: UserId)
        : immutable.Seq[Post] = {
    val (postAfter, wasHidden) = doFlagPost(pageId, postNr, flagType, flaggerId = flaggerId)
    var postsHidden = ifBadAuthorCensorEverything(postAfter)
    if (wasHidden) {
      postsHidden :+= postAfter
    }
    postsHidden
  }


  private def doFlagPost(pageId: PageId, postNr: PostNr, flagType: PostFlagType,
        flaggerId: UserId): (Post, Boolean) = {
    readWriteTransaction { transaction =>
      val flagger = transaction.loadTheMember(flaggerId)
      val postBefore = transaction.loadThePost(pageId, postNr)
      val pageMeta = transaction.loadThePageMeta(pageId)
      val categories = transaction.loadCategoryPathRootLast(pageMeta.categoryId)
      val settings = loadWholeSiteSettings(transaction)

      dieOrThrowNoUnless(Authz.mayFlagPost(
        flagger, transaction.loadGroupIds(flagger),
        postBefore, pageMeta, transaction.loadAnyPrivateGroupTalkMembers(pageMeta),
        inCategoriesRootLast = categories,
        permissions = transaction.loadPermsOnPages()), "EdEZBXKSM2")

      val newNumFlags = postBefore.numPendingFlags + 1
      var postAfter = postBefore.copy(numPendingFlags = newNumFlags)

      val reviewTask = makeReviewTask(flaggerId, postAfter,
        immutable.Seq(ReviewReason.PostFlagged), transaction)

      // Hide post, update page?
      val shallHide = newNumFlags >= settings.numFlagsToHidePost && !postBefore.isBodyHidden
      if (shallHide) {
        hidePostsOnPage(Vector(postAfter), pageId, "This post was flagged")(transaction)
      }
      else {
        transaction.updatePost(postAfter)
      }

      transaction.insertFlag(postBefore.id, pageId, postNr, flagType, flaggerId)
      transaction.upsertReviewTask(reviewTask)
      (postAfter, shallHide)
    }
  }


  /** Hides all posts this user has made, if s/he is a new user that gets flagged a lot.
    */
  private def ifBadAuthorCensorEverything(post: Post): immutable.Seq[Post] = {
    val userId = post.createdById
    val pageIdsToRefresh = mutable.Set[PageId]()
    val postsHidden = readWriteTransaction { transaction =>
      val user = transaction.loadUser(userId) getOrDie "EdE6FKW02"
      if (user.effectiveTrustLevel != TrustLevel.NewMember)
        return Nil

      // Keep small, there's an O(n^2) loop below (6WKUT02).
      val numThings = 100
      val settings = loadWholeSiteSettings(transaction)

      // For members, we'll use the user id.  For guests, we'll use the browser-ip & -id-cookie.
      var anyBrowserIdData: Option[BrowserIdData] = None
      def theBrowserIdData = anyBrowserIdData getOrDie "EdE5RW2EB8"
      var guestPostIds = Set[PostId]()

      var tasks =
        if (user.isMember) {
          transaction.loadReviewTasksAboutUser(user.id, limit = numThings,
            orderBy = OrderBy.MostRecentFirst)
        }
        else {
          val auditLogEntry = transaction.loadCreatePostAuditLogEntry(post.id) getOrElse {
            // Audit log data apparently deleted, so cannot find out if the guest author is bad.
            return Nil
          }
          anyBrowserIdData = Some(auditLogEntry.browserIdData)
          guestPostIds = loadPostIdsByGuestBrowser(theBrowserIdData, limit = numThings,
              orderBy = OrderBy.MostRecentFirst)(transaction)
          transaction.loadReviewTasksAboutPostIds(guestPostIds)
        }

      tasks = tasks.filter(_.reasons.contains(ReviewReason.PostFlagged))

      // If lots of flags are incorrect, then don't censor the user, at this time.
      val numResolvedFine = tasks.count(_.resolution.exists(_.isFine))
      val numResolvedBad = tasks.count(_.resolution.exists(!_.isFine))
      if (numResolvedFine >= math.max(1, numResolvedBad))
        return Nil

      // If there are too few flags, or too few distinct human flaggers, don't censor the user.
      val maybeBadTasks = tasks.filter(!_.resolution.exists(_.isFine))
      val manyFlags = maybeBadTasks.size >= settings.numFlagsToBlockNewUser
      val flaggersMaybeInclSystem = maybeBadTasks.map(_.createdById).toSet
      val numFlaggersExclSystem = (flaggersMaybeInclSystem - SystemUserId).size
      val manyFlaggers = numFlaggersExclSystem >= settings.numFlaggersToBlockNewUser
      if (!manyFlags || !manyFlaggers)
        return Nil

      // Block the user.
      if (user.isMember) {
        COULD_OPTIMIZE // edit & save the user directly [6DCU0WYX2]
        val member = transaction.loadMemberInclDetails(user.id) getOrDie "EdE5KW0U4"
        val memberAfter = member.copyWithMaxThreatLevel(ThreatLevel.ModerateThreat)
        transaction.updateMemberInclDetails(memberAfter)
      }
      else {
        blockGuestImpl(theBrowserIdData, user.id, numDays = 31,
          threatLevel = ThreatLevel.ModerateThreat, blockerId = SystemUserId)(transaction)
      }

      SECURITY ; BUG // minor: if the author has posted > numThings post, only the most recent ones
      // will get hidden here, because we loaded only the most recent ones, above.
      // — However, new users are rate limited, so not super likely to happen.

      // Censor the user's posts.
      val postToMaybeHide =
        if (user.isMember) {
          transaction.loadPostsByAuthorSkipTitles(
              userId, limit = numThings, OrderBy.MostRecentFirst).filter(!_.isBodyHidden)
        }
        else {
          transaction.loadPostsByUniqueId(guestPostIds).values.filter(!_.isBodyHidden)
        }

      // Don't hide posts that have been reviewed and deemed okay.
      // (Hmm, could hide them anyway if they were edited later ... oh now gets too complicated.)
      val postToHide = postToMaybeHide filter { post =>
        // This is O(n^2), so keep numThings small (6WKUT02), like <= 100.
        val anyReviewTask = tasks.find(_.postId.contains(post.id))
        !anyReviewTask.exists(_.resolution.exists(_.isFine))
      }

      val postToHideByPage = postToHide.groupBy(_.pageId)
      for ((pageId, posts) <- postToHideByPage) {
        hidePostsOnPage(posts, pageId, "Many posts by this author got flagged, hiding all")(
            transaction)
        pageIdsToRefresh += pageId
      }
      postToHide
    }

    removeUserFromMemCache(userId)
    pageIdsToRefresh.foreach(refreshPageInMemCache)
    postsHidden.to[immutable.Seq]
  }


  /** Finds posts created by a certain browser, by searching for create-post audit log entries
    * by that browser (ip address and browser-id-cookie, perhaps fingerprint later).
    */
  private def loadPostIdsByGuestBrowser(browserIdData: BrowserIdData, limit: Int,
        orderBy: OrderBy)(transaction: SiteTransaction): Set[PostId] = {
    val manyEntries = transaction.loadCreatePostAuditLogEntriesBy(
      browserIdData, limit = limit, orderBy)
    val fewerEntries = manyEntries filter { entry =>
      User.isGuestId(entry.doerId) && !entry.postNr.contains(PageParts.TitleNr)
    }
    fewerEntries.flatMap(_.uniquePostId).toSet
  }


  private def hidePostsOnPage(posts: Iterable[Post], pageId: PageId, reason: String)(
        transaction: SiteTransaction) {
    dieIf(posts.exists(_.pageId != pageId), "EdE7GKU23Y4")
    dieIf(posts.exists(_.isTitle), "EdE5KP0WY2") ; SECURITY ; ANNOYING // end users can trigger internal error
    val postsToHide = posts.filter(!_.isBodyHidden)
    if (postsToHide.isEmpty)
      return

    val pageMetaBefore = transaction.loadPageMeta(pageId) getOrDie "EdE7KP0F2"
    var numOrigPostRepliesHidden = 0
    var numRepliesHidden = 0
    var isHidingOrigPost = false

    postsToHide foreach { postBefore =>
      numOrigPostRepliesHidden += (postBefore.isVisible && postBefore.isOrigPostReply) ? 1 | 0
      numRepliesHidden += (postBefore.isVisible && postBefore.isReply) ? 1 | 0
      isHidingOrigPost ||= postBefore.isOrigPost

      val postAfter = postBefore.copy(
        bodyHiddenAt = Some(transaction.now.toJavaDate),
        bodyHiddenById = Some(SystemUserId),
        bodyHiddenReason = Some(reason))

      transaction.updatePost(postAfter)
    }

    var pageMetaAfter = pageMetaBefore.copy(
      numRepliesVisible = pageMetaBefore.numRepliesVisible - numRepliesHidden,
      numOrigPostRepliesVisible =
          pageMetaBefore.numOrigPostRepliesVisible - numOrigPostRepliesHidden)

    // If none of the posts were visible (e.g. because deleted already), we don't need
    // to update the page meta.
    if (pageMetaAfter != pageMetaBefore || isHidingOrigPost) {
      pageMetaAfter = pageMetaAfter.copy(version = pageMetaBefore.version + 1)

      // Hide page if everything on it hidden.
      if (!pageMetaBefore.isHidden && pageMetaAfter.numRepliesVisible == 0) {
        val willOrigPostBeVisible = if (isHidingOrigPost) false else {
          val anyOrigPost = transaction.loadOrigPost(pageId)
          anyOrigPost.exists(_.isVisible)
        }
        if (!willOrigPostBeVisible) {
          pageMetaAfter = pageMetaAfter.copy(hiddenAt = Some(transaction.now))
        }
      }

      transaction.updatePageMeta(pageMetaAfter, oldMeta = pageMetaBefore,
        // The page might be hidden now, or num-replies has changed, so refresh forum topic list.
        markSectionPageStale = true)
      updatePagePopularity(PagePartsDao(pageId, transaction), transaction)
    }
  }


  def clearFlags(pageId: PageId, postNr: PostNr, clearedById: UserId): Unit = {
    readWriteTransaction { transaction =>
      val clearer = transaction.loadTheUser(clearedById)
      if (!clearer.isStaff)
        throwForbidden("EsE7YKG59", "Only staff may clear flags")

      val postBefore = transaction.loadThePost(pageId, postNr)
      val postAfter = postBefore.copy(
        numPendingFlags = 0,
        numHandledFlags = postBefore.numHandledFlags + postBefore.numPendingFlags)
      transaction.updatePost(postAfter)
      transaction.clearFlags(pageId, postNr, clearedById = clearedById)
      // Need not update page version: flags aren't shown (except perhaps for staff users).
    }
    // In case the post gets unhidden now when flags gone:
    refreshPageInMemCache(pageId)
  }


  def loadPostsReadStats(pageId: PageId): PostsReadStats =
    readOnlyTransaction(_.loadPostsReadStats(pageId))


  def loadPost(pageId: PageId, postNr: PostNr): Option[Post] =
    readOnlyTransaction(_.loadPost(pageId, postNr))


  /** Finds all of postNrs. If any single one (or more) is missing, returns Error. */
  def loadPostsAllOrError(pageId: PageId, postNrs: Iterable[PostNr])
        : immutable.Seq[Post] Or One[PostNr] =
    readOnlyTransaction { tx =>
      val posts = tx.loadPosts(postNrs.map(PagePostNr(pageId, _)))
      dieIf(posts.length > postNrs.size, "EdE2WBR57")
      if (posts.length < postNrs.size) {
        val firstMissing = postNrs.find(nr => !posts.exists(_.nr == nr)) getOrDie "EdE7UKYWJ2"
        return Bad(One(firstMissing))
      }
      Good(posts)
    }


  private def updateVoteCounts(pageParts: PageParts, post: Post, transaction: SiteTransaction) {
    val actions = transaction.loadActionsDoneToPost(post.pageId, postNr = post.nr)
    val readStats = transaction.loadPostsReadStats(post.pageId, Some(post.nr))
    val postAfter = post.copyWithUpdatedVoteAndReadCounts(actions, readStats)

    val numNewLikes = postAfter.numLikeVotes - post.numLikeVotes
    val numNewWrongs = postAfter.numWrongVotes - post.numWrongVotes
    val numNewBurys = postAfter.numBuryVotes - post.numBuryVotes
    val numNewUnwanteds = postAfter.numUnwantedVotes - post.numUnwantedVotes

    val (numNewOpLikes, numNewOpWrongs, numNewOpBurys, numNewOpUnwanteds) =
      if (post.isOrigPost)
        (numNewLikes, numNewWrongs, numNewBurys, numNewUnwanteds)
      else
        (0, 0, 0, 0)

    val pageMetaBefore = transaction.loadThePageMeta(post.pageId)
    val pageMetaAfter = pageMetaBefore.copy(
      numLikes = pageMetaBefore.numLikes + numNewLikes,
      numWrongs = pageMetaBefore.numWrongs + numNewWrongs,
      numBurys = pageMetaBefore.numBurys + numNewBurys,
      numUnwanteds = pageMetaBefore.numUnwanteds + numNewUnwanteds,
      // For now: use max() because the db fields were just added so some counts are off.
      // (but not for Unwanted, that vote was added after the vote count fields)
      numOrigPostLikeVotes = math.max(0, pageMetaBefore.numOrigPostLikeVotes + numNewOpLikes),
      numOrigPostWrongVotes = math.max(0, pageMetaBefore.numOrigPostWrongVotes + numNewOpWrongs),
      numOrigPostBuryVotes = math.max(0, pageMetaBefore.numOrigPostBuryVotes + numNewOpBurys),
      numOrigPostUnwantedVotes = pageMetaBefore.numOrigPostUnwantedVotes + numNewOpUnwanteds,
      version = pageMetaBefore.version + 1)

    // (Don't reindex)
    transaction.updatePost(postAfter)
    transaction.updatePageMeta(pageMetaAfter, oldMeta = pageMetaBefore, markSectionPageStale = true)
    updatePagePopularity(pageParts, transaction)

    // COULD split e.g. num_like_votes into ..._total and ..._unique? And update here.
  }

}



object PostsDao {

  private val SixMinutesMs = 6 * 60 * 1000
  private val OneHourMs = SixMinutesMs * 10
  private val OneDayMs = OneHourMs * 24

  val HardMaxNinjaEditWindowMs = OneDayMs

  /** For non-discussion pages, uses a long ninja edit window.
    */
  def ninjaEditWindowMsFor(pageRole: PageRole): Int = pageRole match {
    case PageRole.CustomHtmlPage => OneHourMs
    case PageRole.WebPage => OneHourMs
    case PageRole.Code => OneHourMs
    case PageRole.SpecialContent => OneHourMs
    case PageRole.Blog => OneHourMs
    case PageRole.Forum => OneHourMs
    case _ => SixMinutesMs
  }


  def makeReviewTask(createdById: UserId, post: Post, reasons: immutable.Seq[ReviewReason],
        transaction: SiteTransaction): ReviewTask = {
    val oldReviewTask = transaction.loadPendingPostReviewTask(post.id,
      taskCreatedById = createdById)
    val newTask = ReviewTask(
      id = oldReviewTask.map(_.id).getOrElse(transaction.nextReviewTaskId()),
      reasons = reasons,
      createdById = createdById,
      createdAt = transaction.now.toJavaDate,
      createdAtRevNr = Some(post.currentRevisionNr),
      maybeBadUserId = post.createdById,
      postId = Some(post.id),
      postNr = Some(post.nr))
    newTask.mergeWithAny(oldReviewTask)
  }

}

