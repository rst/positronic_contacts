package org.positronicnet.sample.contacts

import org.positronicnet.ui._
import android.media.RingtoneManager
import android.net.Uri

class CallOptionsActivity 
  extends PositronicActivity( layoutResourceId = R.layout.call_options )
  with TypedViewHolder
{
  onCreate {
    val contactSlug = getIntent.getSerializableExtra( "contact" )
    val contact = contactSlug.asInstanceOf[ Contact ]
    ContactsActivityUiBinder.show( contact, findView( TR.call_options ))
    updateRingtone( contact.customRingtone )
  }

  def updateRingtone( ringtoneUriStr: String ) = 
    findView( TR.ringtoneLabel ).setText(
      if (ringtoneUriStr == null || ringtoneUriStr == "")
        "(default)"
      else
        RingtoneManager.getRingtone( this, Uri.parse( ringtoneUriStr )).getTitle( this )
    )
}
