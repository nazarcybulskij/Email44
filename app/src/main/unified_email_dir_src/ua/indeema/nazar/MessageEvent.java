package ua.indeema.nazar;

import com.android.mail.browse.ConversationMessage;

/**
 * Created by nazar on 06.04.15.
 */
public class MessageEvent {

    public MessageEvent(ConversationMessage  i, String name) {
        this.i = i;
        this.name = name;
    }

    public ConversationMessage i;
    String name;
}
