package org.positronicnet.sample.contacts

import org.positronicnet.content._
import org.positronicnet.orm._
import org.positronicnet.notifications._
import org.positronicnet.notifications.Actions._
import org.positronicnet.orm.Actions._

import org.positronicnet.util.ReflectiveProperties
import org.positronicnet.util.ReflectUtils

import android.content.Context
import android.util.{AttributeSet, Log}

import android.provider.{ContactsContract => CC}
import android.provider.ContactsContract.CommonDataKinds

import android.text.TextUtils
import android.graphics.BitmapFactory

import android.net.Uri

// Contacts table.  Most columns are maintained by the provider, and
// read-only...

case class Contact (
  val lookupKey:          String            = "",
  val displayNamePrimary: String            = "",
  val photoId:            RecordId[Photo]   = ContactData.photos.unsavedId,
  val photoUri:           String            = "",
  val photoThumbnailUri:  String            = "",
  val inVisibleGroup:     Boolean           = false,
  val starred:            Boolean           = false,
  val hasPhoneNumber:     Boolean           = false,
  val customRingtone:     String            = "",
  val sendToVoicemail:    Boolean           = false,
  val id:                 RecordId[Contact] = Contacts.unsavedId
) 
extends ManagedRecord with ReflectiveProperties
{
  lazy val lcDisplayName = displayNamePrimary.toLowerCase // for filtering

  @transient lazy val rawContacts = 
    new HasMany( RawContacts, 
                 ReflectUtils.getStatic[ String, CC.RawContacts ]("CONTACT_ID"))

  @transient lazy val data = 
    new HasMany( ContactData, 
                 ReflectUtils.getStatic[ String, CC.Data ]("CONTACT_ID"))

  @transient lazy val photoQuery =
    if (this.photoId.id != 0)
      (ContactData.photos ? FindById( this.photoId ))
    else
      Future( new Photo )

  @transient lazy val lookupUri =
    CC.Contacts.getLookupUri( this.id.id, this.lookupKey )
}

object Contacts
  extends RecordManagerForFields[ Contact, CC.Contacts ]
{
  private val col = ReflectUtils.getStatics[ String, CC.Contacts ]

  // Most fields read-only; declare the ones we want to write,
  // for call options...

  defaultFieldMapping( MapAs.ReadOnly )
  mapField( "sendToVoicemail", col("SEND_TO_VOICEMAIL"), MapAs.ReadWrite )
  mapField( "customRingtone",  col("CUSTOM_RINGTONE"),   MapAs.ReadWrite )
}

// Kludge pseudo-action to get a contact from a URI.  
// Suggests the need for further work on the plumbing upstream...

case class FindContactFromUri( uri: Uri )
  extends ScopeQueryAction[ Contact, Contact ]
{
  val complete : PartialFunction[ BaseNotifierImpl[ IndexedSeq[ Contact ]], Contact ] = {
    case _ =>
      val query = PositronicContentResolver( uri )
      Contacts.fetchRecords( query )(0)
  }
}

// Raw-contacts table.
//
// Actual individual contacts, associated with accounts, which the
// provider aggregates into the globs that are presented to users.

case class RawContact (
  val contactId:       RecordId[Contact]    = Contacts.unsavedId,
  val starred:         Boolean              = false,
  val customRingtone:  String               = null,
  val sendToVoicemail: Boolean              = false,
  val deleted:         Boolean              = false,
  val accountName:     String               = null,
  val accountType:     String               = null,
  val id:              RecordId[RawContact] = RawContacts.unsavedId
) 
extends ManagedRecord with ReflectiveProperties
{
  @transient lazy val data = 
    new HasMany( ContactData, 
                 ReflectUtils.getStatic[ String, CC.Data ]("RAW_CONTACT_ID"))
}

object RawContacts
  extends RecordManagerForFields[ RawContact, CC.RawContacts ]
{
  // Column names are inherited from protected static classes where
  // they're defined, and Scala has trouble fishing them out.  So,
  // we do this to get them by reflection at runtime. 

  private val col = ReflectUtils.getStatics[ String, CC.RawContacts ]

  // Most columns we're mapping are read/write.  The ID is read-only 
  // as usual, and we have these additional exceptions:

  mapField( "contactId",   col("CONTACT_ID"),   MapAs.ReadOnly  )
  mapField( "accountName", col("ACCOUNT_NAME"), MapAs.WriteOnce )
  mapField( "accountType", col("ACCOUNT_TYPE"), MapAs.WriteOnce )

  // Query to retrieve raw contacts for a contact.  *Should* use
  // lookup key, to try to compensate for the results of background
  // syncs, but this is OK for testing...

  def forContact( contact: Contact ) =
    this.whereEq( col("CONTACT_ID") -> contact.id )
}

// Groups table.
//
// Groups right now are per account.  We're mapping a subset...

class Group extends ManagedRecord {

  val accountName:  String          = null
  val accountType:  String          = null
  val title:        String          = null
  val notes:        String          = null
  val groupVisible: Boolean         = false
  val id:           RecordId[Group] = Groups.unsavedId

  override def toString = "Group: " + 
    (if (!groupVisible) "[INVIS] " else "") +
    accountName + " " + accountType + " '" +
    title + "' '" + notes + "'"
}

object Groups extends RecordManagerForFields[ Group, CC.Groups ]
{
  private val col = ReflectUtils.getStatics[ String, CC.Groups ]

  mapField( "accountName", col("ACCOUNT_NAME"), MapAs.WriteOnce )
  mapField( "accountType", col("ACCOUNT_TYPE"), MapAs.WriteOnce )
}

// Contact-data table.  
//
// Note that "mimetype" isn't mapped for any subtype other than the
// catch-all; instead, we treat it as a discriminant that the
// variant-record machinery handles on its own.

abstract class ContactData 
  extends ManagedRecord with ReflectiveProperties with Serializable
{
  // Generic fields...

  val contactId:      RecordId[ Contact ]    = Contacts.unsavedId
  val rawContactId:   RecordId[ RawContact ] = RawContacts.unsavedId
  val isPrimary:      Boolean                = false
  val isSuperPrimary: Boolean                = false
  val dataVersion:    Int                    = -1 // read-only

  // Mime type for this content item, as the discriminant which
  // the variant machinery manages...

  def typeTag = 
    this.id.mgr.asInstanceOf[ ContactData.DataKindMapper[_,_] ].discriminant

  // Code to determine "equivalence class" of an item --- if two rawContacts
  // have the same phone number, and a user is viewing the aggregate, we 
  // want to show it only once.  And, ideally, that will encompass trivial
  // variations (e.g., in white space).  So, this method returns an AnyRef,
  // which is some object that will be the same for all data of a particular
  // type in the same "equivalence class".
  //
  // Default is the object's ID, which won't be equivalent to anything else.

  def equivalenceKey: AnyRef = this.id

  // Routine to allow editor code to determine if this record is "empty",
  // and should not be saved.

  def isEmpty: Boolean

  // Utility for 'isEmpty' routines...

  def isBlank( s: String ) = 
    s == null || s == "" || s.trim == ""

  // More informative 'toString'

  override def toString = 
    super.toString + " id: " + id + " rcid: " + rawContactId
}

// "Structured name" records.  Note the special-case treatment of columns
// on insert/update.  If we submit a display name *and nothing else*, the
// content provider will attempt to fill in the first name, last name, etc.,
// based on splitting the display name.  However, submitting even null or
// empty values will prevent that.  So, we rig the record manager to produce
// a mix most likely to get the behavior we want out of the content provider
// in given instances.
//
// XXX: treatment of blank names may or may not be appropriate.
// (We might want to force user to fill in *something* on save.)
// But that's a matter for the ContactEditState...

class StructuredName extends ContactData
{
  val displayName: String = null

  val prefix:     String = null
  val givenName:  String = null
  val middleName: String = null
  val familyName: String = null
  val suffix:     String = null

  val phoneticGivenName:  String = null
  val phoneticMiddleName: String = null
  val phoneticFamilyName: String = null

  val id: RecordId[ StructuredName ] = ContactData.structuredNames.unsavedId

  def isEmpty = 
    isBlank( prefix ) && isBlank( suffix ) &&
    isBlank( givenName ) && isBlank( middleName ) && isBlank( familyName ) &&
    isBlank( phoneticGivenName ) && isBlank( phoneticMiddleName ) &&
    isBlank( phoneticFamilyName )

  override def toString = {
    val components =
      Seq( displayName, prefix, givenName, middleName, familyName, suffix )
    super.toString + " '" + components.reduceLeft(_+"' '"+_) + "'"
  }
}

trait StructuredNameManager[T <: StructuredName] extends BaseRecordManager[T] {

  override def dataPairs( n: T ) = {

    import CommonDataKinds.{StructuredName => SN}

    val basePairs = super.dataPairs(n)

    val snFields = Seq( 
      SN.PREFIX, SN.SUFFIX,
      SN.GIVEN_NAME, SN.MIDDLE_NAME, SN.FAMILY_NAME,
      SN.PHONETIC_GIVEN_NAME, SN.PHONETIC_MIDDLE_NAME, SN.PHONETIC_FAMILY_NAME)

    val snPairs = basePairs.filter{ p => snFields.contains( p._1 )}

    if (snPairs.exists{ _._2 != org.positronicnet.content.CvString(null) })
      basePairs
    else
      basePairs.filter{ p => !snFields.contains( p._1 )}
  }

}

// Group membership records.  For inserts, you can set a "source id"
// as opposed to the actual group ID; we're not bothering with this
// for now.

class GroupMembership extends ContactData
{
  val groupRowId: RecordId[Group] = Groups.unsavedId
  val id: RecordId[GroupMembership] = ContactData.groupMemberships.unsavedId

  override def toString = super.toString + " group id: " + groupRowId

  def isEmpty = false
}

// Photos.  Up through Gingerbread, there's only one data field, the
// smallest photo data (which ICS docs refer to as the "thumbnail").
// So, that's what we're handling for now...

class Photo extends ContactData
{
  val id: RecordId[Photo] = ContactData.photos.unsavedId
  val photo: Array[Byte]  = null

  @transient lazy val thumbnailBitmap =
    BitmapFactory.decodeByteArray( photo, 0, photo.length )

  def isEmpty = (photo == null)
}

// Nicknames.  These have an internal "category label", as we're calling
// it, but the stock contacts app forces it to "default".  For now, we do
// likewise, if only to avoid producing something the SyncAdapter might
// gag on...

class Nickname extends ContactData
{
  val id: RecordId[Nickname] = ContactData.nicknames.unsavedId

  // The actual nickname
  val name: String = null

  // The "CategoryLabel" fields we aren't going to use...
  val `type` = CommonDataKinds.Nickname.TYPE_DEFAULT
  val label: String = null

  def isEmpty = isBlank( name )
  override def equivalenceKey = 
    if (name == null) "" else name.toLowerCase
}

// Website records.  Again, there's an unused pair of "type/label" columns

class Website extends ContactData
{
  val id: RecordId[Website] = ContactData.websites.unsavedId

  val url: String = null

  // The "CategoryLabel" fields we aren't going to use...
  val `type` = CommonDataKinds.Website.TYPE_OTHER
  val label: String = null

  def isEmpty = isBlank( url )
  override def equivalenceKey = if (url == null) "" else url
}

// IM.  These are odd; they have the standard "type/label" columns,
// but the standard app doesn't let you set them; nor (as I write) 
// does the GMail contacts web page.  Instead, what shows up in both
// UIs as what we're calling the category label is a separate pair of
// "protocol/customprotocol" columns, which we expose to the UI as the 
// synthetic field "protocolLabel", a CategoryLabel.
//
// (It actually wouldn't be much trouble for us to support both.  We'd
// need to change the ContentModel stuff to make the lists of supported
// tags depend on the field, and not just the DataKind, but that's an
// easy change.  But again, we're holding off on that for now, to avoid
// creating anything that the syncadapter might gag on.)

class ImAddress extends ContactData
{
  val id: RecordId[ImAddress] = ContactData.imAddresses.unsavedId

  val data: String = null               // Handle.  That's what they call it...

  def isEmpty = isBlank( data )

  // The "CategoryLabel" fields we aren't going to use...

  val `type` = CommonDataKinds.Im.TYPE_OTHER
  val label: String = null

  // The "protocol" fields we will use...

  val protocol: Int = 0
  val customProtocol: String = null

  // And machinery to export those as a CategoryLabel...

  def protocolLabel = CategoryLabel( protocol, customProtocol )

  def protocolLabel_:=( newLabel: CategoryLabel ) = 
    this.setProperty[Int]("protocol", newLabel.tag)
        .setProperty[String]("customProtocol", newLabel.label)
}

// Notes.

class Note extends ContactData {
  val id: RecordId[Note] = ContactData.notes.unsavedId
  val note: String = null
  def isEmpty = isBlank( note )
  override def equivalenceKey = 
    if (note == null) "" else note.toLowerCase
}

// Common machinery for rows that have a "record type", or what 
// we're calling here a "category label", which is jargon for a
// Home/Work/Mobile category, as for phone numbers or email 
// addresses.

abstract class ContactDataWithCategoryLabel extends ContactData 
{
  val categoryTag: Int = 0
  val label:       String = null

  def categoryLabel = CategoryLabel( categoryTag, label )

  def categoryLabel_:=( newLabel: CategoryLabel ) = 
    this.setProperty[Int]("categoryTag", newLabel.tag)
        .setProperty[String]("label", newLabel.label)
}

// Class that represents the value of a "label-or-custom" field.
// These are backed by two underlying fields, one an integer named
// "type" (which we generally style "tag" since "type" is a reserved
// word in Scala), and one the custom label, if any.

case class CategoryLabel( val tag: Int,  val label: String )
{
  def tag_:=( newTag: Int ) = this.copy( tag = newTag ) 
  def label_:=( s: String ) = this.copy( label = s )
}

// Phone records.  

class Phone extends ContactDataWithCategoryLabel {
  val number:  String            = null
  val id:      RecordId[ Phone ] = ContactData.phones.unsavedId

  def isEmpty = isBlank( number )

  override def toString = 
    super.toString + " ("+ categoryTag +", "+ number +")"

  override def equivalenceKey = 
    if (number == null) "" else number.filter{ _.isDigit }
}

// Email records.  

class Email extends ContactDataWithCategoryLabel {
  val address: String = null
  val id:      RecordId[ Email ] = ContactData.emails.unsavedId

  def isEmpty = isBlank( address )

  override def toString = 
    super.toString + " ("+ categoryTag +", "+ address+")"

  override def equivalenceKey = 
    if (address == null) "" else address.toLowerCase
}

// Mailing address records.

class Postal extends ContactDataWithCategoryLabel {

  val street:       String = null
  val pobox:        String = null
  val neighborhood: String = null
  val city:         String = null
  val region:       String = null
  val postcode:     String = null
  val country:      String = null

  val id:           RecordId[ Postal ] = ContactData.postals.unsavedId

  def isEmpty = 
    isBlank( street ) && isBlank( pobox ) && isBlank( neighborhood ) &&
    isBlank( city ) && isBlank( region ) && isBlank( postcode ) &&
    isBlank( country )
}

// Organization records.

class Organization extends ContactDataWithCategoryLabel {

  val company:        String = null
  val title:          String = null
  val department:     String = null
  val jobDescription: String = null
  val symbol:         String = null
  val officeLocation: String = null
  val phoneticName:   String = null

  val id: RecordId[ Organization ] = ContactData.organizations.unsavedId

  def isEmpty = 
    isBlank( company ) && isBlank( title ) && isBlank( department ) &&
    isBlank( jobDescription ) && isBlank( symbol ) && 
    isBlank( officeLocation ) && isBlank( phoneticName )

  override def equivalenceKey = 
    (if (company == null) "" else company) + 
    (if (title   == null) "" else title)
}

// Unknown data records.  There's actually a defined way for third-party
// apps to specify how to display these, which is undocumented, and changed
// in a major way with ICS...

class UnknownData extends ContactData {

  val mimetype: String = null
  val data1:    String = null
  val id:       RecordId[ UnknownData ] = ContactData.unknowns.unsavedId

  override def typeTag = mimetype

  def isEmpty = false
}

// Record mapper for the whole shebang.

object ContactData
  extends VariantRecordManager[ ContactData ](
    PositronicContentResolver( CC.Data.CONTENT_URI ),
    "mimetype" // documented value of ContactsContract.DataColumns.MIMETYPE
  )
{
  val CONTACT_ID = ReflectUtils.getStatic[ String, CC.Data ]("CONTACT_ID")

  class DataKindMapper[ TRec <: ContactData : ClassManifest,
                        TKind : ClassManifest ]
    extends TaggedVariantForFields[ TRec, TKind ](
      ReflectUtils.getStatic[ String, TKind ]("CONTENT_ITEM_TYPE")
    ) 
  {
    mapField( "contactId", CONTACT_ID, MapAs.ReadOnly )
    mapField( "dataVersion", 
              ReflectUtils.getStatic[ String, CC.Data ]("DATA_VERSION"),
              MapAs.ReadOnly )
  }

  class TypedDataKindMapper[ TRec <: ContactDataWithCategoryLabel:ClassManifest,
                             TKind : ClassManifest ]
    extends DataKindMapper[ TRec, TKind ]
  {
    mapField( "contactId", CONTACT_ID, MapAs.ReadOnly )
    mapField( "categoryTag", ReflectUtils.getStatic[ String, TKind ]("TYPE") ) 
    mapField( "dataVersion", 
              ReflectUtils.getStatic[ String, CC.Data ]("DATA_VERSION"),
              MapAs.ReadOnly )
  }

  val phones  = new TypedDataKindMapper[ Phone, CommonDataKinds.Phone ] 
  val emails  = new TypedDataKindMapper[ Email, CommonDataKinds.Email ] 
  val postals = new TypedDataKindMapper[ Postal, 
                                         CommonDataKinds.StructuredPostal ] 
  val organizations = new TypedDataKindMapper[ Organization,
                                               CommonDataKinds.Organization ]

  val nicknames   = new DataKindMapper[ Nickname,  CommonDataKinds.Nickname ]
  val websites    = new DataKindMapper[ Website,   CommonDataKinds.Website  ]
  val notes       = new DataKindMapper[ Note,      CommonDataKinds.Note     ]
  val imAddresses = new DataKindMapper[ ImAddress, CommonDataKinds.Im       ]
  val photos      = new DataKindMapper[ Photo,     CommonDataKinds.Photo    ]

  val groupMemberships =
    new DataKindMapper[ GroupMembership, CommonDataKinds.GroupMembership ]
  val structuredNames = 
    new DataKindMapper[ StructuredName, CommonDataKinds.StructuredName ]
      with StructuredNameManager[ StructuredName ]

  val unknowns = 
    new CatchAllVariantForFields[ UnknownData, CC.Data ] {
      mapField( "dataVersion", 
                ReflectUtils.getStatic[ String, CC.Data ]("DATA_VERSION"),
                MapAs.ReadOnly )
    }
}


