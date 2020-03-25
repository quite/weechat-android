// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.

package com.ubergeek42.WeechatAndroid.relay;

import android.graphics.Typeface;
import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import com.ubergeek42.WeechatAndroid.service.P;
import com.ubergeek42.WeechatAndroid.utils.Linkify;
import com.ubergeek42.weechat.Color;
import com.ubergeek42.weechat.ColorScheme;
import com.ubergeek42.weechat.relay.protocol.HdataEntry;
import com.ubergeek42.weechat.relay.protocol.RelayObject;

import java.text.DateFormat;
import java.util.Date;

public class Line {
    public enum Type {
        OTHER,
        INCOMING_MESSAGE,
        OUTCOMING_MESSAGE
    }

    final public long pointer;
    final public Type type;
    final Date date;
    final private String rawPrefix;
    final private String rawMessage;
    final private @Nullable String nick;
    final boolean isVisible;
    final public boolean isHighlighted;
    final boolean isAction;
    final private boolean isPrivmsg;

    // the sole purpose of this is to prevent onClick event on inner URLSpans to be fired when the
    // user long-presses on the screen and a context menu is shown
    public boolean clickDisabled = false;

    Line(long pointer, Type type, Date date, @NonNull String rawPrefix, @NonNull String rawMessage,
            @Nullable String nick, boolean visible, boolean isHighlighted, boolean isPrivmsg, boolean isAction) {
        this.pointer = pointer;
        this.type = type;
        this.date = date;
        this.rawPrefix = rawPrefix;
        this.rawMessage = rawMessage;
        this.nick = nick;
        this.isVisible = visible;
        this.isHighlighted = isHighlighted;
        this.isPrivmsg = isPrivmsg;
        this.isAction = isAction;
    }

    @WorkerThread public static @NonNull Line make(@NonNull HdataEntry entry) {
        long pointer = entry.getPointerLong();
        String message = entry.getItem("message").asString();
        String prefix = entry.getItem("prefix").asString();
        boolean visible = entry.getItem("displayed").asChar() == 0x01;
        Date date = entry.getItem("date").asTime();
        RelayObject high = entry.getItem("highlight");
        boolean highlighted = high != null && high.asChar() == 0x01;
        RelayObject tagsItem = entry.getItem("tags_array");
        String[] tags = tagsItem != null && tagsItem.getType() == RelayObject.WType.ARR ?
                tagsItem.asArray().asStringArray() : null;

        message = message == null ? "" : message;
        prefix = prefix == null ? "" : prefix;
        String nick = null;
        boolean isPrivmsg = false;
        boolean isAction = false;
        Type type;

        if (tags != null) {
            boolean log1 = false;
            boolean notifyNone = false;

            for (String tag : tags) {
                if      (tag.equals("log1"))        log1 = true;
                else if (tag.equals("notify_none")) notifyNone = true;
                else if (tag.startsWith("nick_"))   nick = tag.substring(5);
                else if (tag.endsWith("_privmsg"))  isPrivmsg = true;
                else if (tag.endsWith("_action"))   isAction = true;
            }

            if (tags.length == 0 || !log1) {
                type = Type.OTHER;
            } else {
                // Every "message" to user should have one or more of these tags
                // notifyNone, notify_highlight or notify_message
                type = notifyNone ? Type.OUTCOMING_MESSAGE : Type.INCOMING_MESSAGE;
            }
        } else {
            // there are no tags, it's probably an old version of weechat, so we err
            // on the safe side and treat it as from human
            type = Type.INCOMING_MESSAGE;
        }

        return new Line(pointer, type, date, prefix, message, nick, visible, highlighted, isPrivmsg,
                isAction);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private String prefixString = null;
    private String messageString = null;
    volatile private Spannable spannable = null;

    @AnyThread void eraseProcessedMessage() {
        spannable = null;
        prefixString = messageString = null;
    }

    @AnyThread void ensureProcessed() {
        if (spannable == null) forceProcessMessage();
    }

    @AnyThread void forceProcessMessage() {
        DateFormat dateFormat = P.dateFormat;
        String timestamp = dateFormat == null ? null : dateFormat.format(date);
        boolean encloseNick = P.encloseNick && isPrivmsg && !isAction;
        Color color = new Color(timestamp, rawPrefix, rawMessage, encloseNick, isHighlighted, P.maxWidth, P.align);
        Spannable spannable = new SpannableString(color.lineString);

        if (type == Type.OTHER && P.dimDownNonHumanLines) {
            spannable.setSpan(new ForegroundColorSpan(ColorScheme.get().chat_inactive_buffer[0] |
                    0xFF000000), 0, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            CharacterStyle droidSpan;
            for (Color.Span span : color.finalSpanList) {
                switch (span.type) {
                    case Color.Span.FGCOLOR:   droidSpan = new ForegroundColorSpan(span.color | 0xFF000000); break;
                    case Color.Span.BGCOLOR:   droidSpan = new BackgroundColorSpan(span.color | 0xFF000000); break;
                    case Color.Span.ITALIC:    droidSpan = new StyleSpan(Typeface.ITALIC); break;
                    case Color.Span.BOLD:      droidSpan = new StyleSpan(Typeface.BOLD); break;
                    case Color.Span.UNDERLINE: droidSpan = new UnderlineSpan(); break;
                    default: continue;
                }
                spannable.setSpan(droidSpan, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        if (P.align != Color.ALIGN_NONE) {
            LeadingMarginSpan margin_span = new LeadingMarginSpan.Standard(0, (int) (P.letterWidth * color.margin));
            spannable.setSpan(margin_span, 0, spannable.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        Linkify.linkify(spannable, color.messageString);

        this.prefixString = color.prefixString;
        this.messageString = color.messageString;
        this.spannable = spannable;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public @Nullable String getNick() {
        return nick;
    }

    public @NonNull String getPrefixString() {
        ensureProcessed();
        return prefixString;
    }

    public @NonNull String getMessageString() {
        ensureProcessed();
        return messageString;
    }

    public @NonNull String getIrcLikeString() {
        return String.format((!isPrivmsg || isAction) ? "%s %s" : "<%s> %s",
                getPrefixString(),
                getMessageString());
    }

    public @NonNull Spannable getSpannable() {
        ensureProcessed();
        return spannable;
    }

    @NonNull @Override public String toString() {
        return "Line(0x" + Long.toHexString(pointer) + ": " + getIrcLikeString() + ")";
    }
}
