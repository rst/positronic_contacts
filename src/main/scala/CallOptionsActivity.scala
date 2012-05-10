package org.positronicnet.sample.contacts

import org.positronicnet.ui._
import org.positronicnet.orm.Actions._

import android.media.RingtoneManager
import android.net.Uri

class CallOptionsActivity 
  extends PositronicActivity( layoutResourceId = R.layout.call_options )
  with TypedViewHolder
{
  onCreate {
    val contactSlug = getIntent.getSerializableExtra( "contact" )
    useContact( contactSlug.asInstanceOf[ Contact ] )
    findView( TR.ringtoneLabel ).onClick{ chooseRingtone }
    findView( TR.sendToVoicemail ).onClick{ toggleSendToVoicemail }
  }

  var workingCopy: Contact = new Contact

  def useContact( contact: Contact ) = {
    workingCopy = contact
    ContactsActivityUiBinder.show( contact, findView( TR.call_options ))

    val ringtoneUriStr = contact.customRingtone

    findView( TR.ringtoneLabel ).setText(
      if (ringtoneUriStr == null || ringtoneUriStr == "")
        getResources.getString( R.string.default_ringtone )
      else
        RingtoneManager.getRingtone( this, Uri.parse( ringtoneUriStr )).getTitle( this )
    )
  }

  def toggleSendToVoicemail = {
    val currentValue = workingCopy.sendToVoicemail
    val newState = workingCopy.copy( sendToVoicemail = !currentValue )
    Contacts ! Save( newState )
    useContact( newState )
  }

  def chooseRingtone = {
    toastLong("not yet")
  }
}
