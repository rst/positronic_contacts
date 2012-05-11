package org.positronicnet.sample.contacts

import org.positronicnet.ui._
import org.positronicnet.notifications.Actions._
import org.positronicnet.notifications.Future
import org.positronicnet.content.PositronicContentResolver

import android.util.Log
import android.os.Bundle
import android.content.Context
import android.view.{View, LayoutInflater}
import android.net.Uri

// Superclass of activities which manipulate data pertaining to an
// individual aggregated contact.

abstract class AggregatedContactActivity( layoutResourceId: Int )
  extends PositronicActivity( layoutResourceId = layoutResourceId )
  with TypedViewHolder
  with ActivityResultDispatch           // for phone/sms... & photo-edit widgets
{
  onCreate {
    useAppFacility( PositronicContentResolver )
    useAppFacility( Res )               // stash a copy of the Resources
  }

  // Methods that must be defined by a subclass to set up for a new
  // AggregateContactEditState, or to synch it with updated state
  // reflected in displayed widgets.

  def bindContactState: Unit
  def syncContactState: Unit

  // Management of our edit state across the Activity lifecycle,
  // including suspend/recreate cycles (due to orientation changes,
  // or whatever else).

  private var currentState: AggregateContactEditState = null
  private var currentContact: Contact = null

  def contactState = currentState       // reader only
  def contactItem = currentContact

  private def setState( contact: Contact,
                        state: AggregateContactEditState ) = {
    this.currentContact = contact
    this.currentState = state
    this.bindContactState
  }

  // Invoked by our helpers if we are *not* restoring state from a prior
  // invocation, and need to do setup.  (From postCreate, but only if
  // onRestoreInstanceState was *not* invoked.)

  override def createInstanceState = {

    val contactUri = getIntent.getData

    if (contactUri == null) {
      setupForNewContact
    }
    else {
      (Contacts ? FindContactFromUri( contactUri )).onSuccess { contact => {
        (contact.rawContacts ? Query).onSuccess { rawContacts => {
          setupFor( contact, rawContacts )
        }}
      }}
    }
  }

  def setupFor( contact: Contact, rawContacts: Seq[RawContact] ) = {
    val dataQueries = rawContacts.map { _.data ? Query }
    Future.sequence( dataQueries ).onSuccess { data => {
      val state = new AggregateContactEditState( rawContacts.zip( data ))
      this.setState( contact, state )
      scrollToTop
    }}
  }
  
  def setupForNewContact = {
    toastLong( "Data Not Found" )
    setupFor( new Contact, Seq( new RawContact ))
  }

  private def scrollToTop = {
    val scroller = findView( TR.scroller )
    if (scroller != null)
      scroller.fullScroll( View.FOCUS_UP )
  }

  // The usual save/restore pair, to handle suspensions.

  override def saveInstanceState( b: Bundle ) = {
    syncContactState
    b.putSerializable( "contact_item", contactItem )
    b.putSerializable( "contact_edit_state", contactState )
  }

  override def restoreInstanceState( b: Bundle ) =
    setState(
      b.getSerializable( "contact_item" ).asInstanceOf[ Contact ],
      b.getSerializable( "contact_edit_state" ).asInstanceOf[ 
        AggregateContactEditState ])

}
