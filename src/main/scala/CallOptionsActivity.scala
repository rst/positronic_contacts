package org.positronicnet.sample.contacts

import org.positronicnet.ui._
import org.positronicnet.orm.Actions._

import android.media.RingtoneManager

import android.net.Uri
import android.content.Intent
import android.app.Activity
import android.util.Log

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
    doUpdate( workingCopy.copy( sendToVoicemail = !currentValue ))
  }

  // Ringtone management.  Only activity result stuff here, so no need
  // for dispatching ceremony...

  val RINGTONE_PICKED = 3003

  def chooseRingtone = {

    val intent = new Intent( RingtoneManager.ACTION_RINGTONE_PICKER )

    // Show default ringtone, show nothing *but* ringtones, don't show
    // "silent".  All per Gingerbread stock behavior...

    intent.putExtra( RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true )
    intent.putExtra( RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false )
    intent.putExtra( RingtoneManager.EXTRA_RINGTONE_TYPE, 
                     RingtoneManager.TYPE_RINGTONE )

    // Tell manager about current value...

    intent.putExtra( RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                     if ( workingCopy.customRingtone != null )
                       Uri.parse( workingCopy.customRingtone )
                     else
                       RingtoneManager.getDefaultUri( 
                         RingtoneManager.TYPE_RINGTONE ))

    startActivityForResult( intent, RINGTONE_PICKED )
  }

  override def onActivityResult( reqCode: Int, resultCode: Int, data: Intent )= 
    if ( resultCode == Activity.RESULT_OK && reqCode == RINGTONE_PICKED ) {
      val uri: Uri = data.getParcelableExtra( 
        RingtoneManager.EXTRA_RINGTONE_PICKED_URI ).asInstanceOf[ Uri ]
      val newVal = if (uri == null) null else uri.toString
      doUpdate( workingCopy.copy( customRingtone = newVal ))
    }

  def doUpdate( newState: Contact ) = {
    Contacts ! Save( newState )
    useContact( newState )
  }
}
