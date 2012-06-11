package org.positronicnet.sample.contacts.test

import org.positronicnet.sample.contacts._

import org.positronicnet.content.PositronicContentResolver
import org.positronicnet.notifications.Future

import org.scalatest._
import org.positronicnet.test.RobolectricTests
import com.xtremelabs.robolectric.Robolectric
import org.scalatest.matchers.ShouldMatchers

import android.provider.ContactsContract
import android.provider.ContactsContract.{CommonDataKinds => CDK}

class TestAccountInfo( atype: String, aname: String )
  extends AccountInfo
{
  val initialGroupQuery = Future[ IndexedSeq[ Group ]]( IndexedSeq.empty )

  // Explicitly document the DataKind limitations that the tests rely on.
  // Phones tightly limited, organization not so much.

  val dataKinds = 
    BaseAccountInfo.dataKinds.toMap
      .updated( CDK.Phone.CONTENT_ITEM_TYPE,
        new DataKindInfo( CDK.Phone.getTypeLabelResource _, maxRecords = 3 ) {
          category( CDK.Phone.TYPE_HOME, maxRecords = 2 )
          category( CDK.Phone.TYPE_WORK, maxRecords = 2 )
          category( CDK.BaseTypes.TYPE_CUSTOM, isCustom = true, maxRecords = 1 )
        })
     .updated( CDK.Email.CONTENT_ITEM_TYPE,
        new DataKindInfo( CDK.Email.getTypeLabelResource _ ) {
          category( CDK.Email.TYPE_HOME )
          category( CDK.Email.TYPE_WORK )
          category( CDK.Email.TYPE_MOBILE )
          category( CDK.Email.TYPE_OTHER )
          category( CDK.BaseTypes.TYPE_CUSTOM, isCustom = true )
        })
     .updated( CDK.Nickname.CONTENT_ITEM_TYPE, new DataKindInfo() )

  System.err.println( dataKinds( CDK.Phone.CONTENT_ITEM_TYPE ).categories )
}

class ModelSpec
  extends Spec
  with ShouldMatchers
  with BeforeAndAfterEach
  with RobolectricTests
{
  AccountInfo.register( "org.positronicnet.test" ){ (rawc) =>
    new TestAccountInfo( rawc.accountType, rawc.accountName )
  }

  override def beforeEach = 
    PositronicContentResolver.openInContext( Robolectric.application )

  override def afterEach = 
    PositronicContentResolver.close

  def phone( number: String, category: CategoryLabel ) = 
    (new Phone).setProperty( "number", number )
               .setProperty( "categoryLabel", category )
      
  def email( address: String, category: CategoryLabel ) = 
    (new Email).setProperty( "address", address )
               .setProperty( "categoryLabel", category )

  def note( text: String ) = (new Note).setProperty( "note", text )

  def homePhone   = CategoryLabel( CDK.Phone.TYPE_HOME, null )
  def workPhone   = CategoryLabel( CDK.Phone.TYPE_WORK, null )
  def customPhone = CategoryLabel( CDK.BaseTypes.TYPE_CUSTOM, "car" )

  def homeEmail   = CategoryLabel( CDK.Email.TYPE_HOME, null )

  describe( "emptiness checks" ) {
    it ("should mark null records as empty") {
      assert( (new Phone).isEmpty )
      assert( (new Email).isEmpty )
      assert( (new Note).isEmpty )
    }
    it ("should treat blank strings as empty") {
      assert( phone( "", homePhone ).isEmpty )
      assert( email( "", homeEmail ).isEmpty )
      assert( note( "" ).isEmpty )
    }
    it ("should not treat 'full' items as empty") {
      assert( !phone( "911", homePhone ).isEmpty )
      assert( !email( "fred@flintstone.com", homeEmail ).isEmpty )
      assert( !note( "needs fish food" ).isEmpty )
    }
  }

  describe( "data aggregation" ) {

    // Set up some test fixtures, throw them up in the air, and
    // assert that things come down in appropriate places and
    // configurations.

    val rawContactA = new RawContact
    val rawContactB = new RawContact
    
    def aggregate( adata: Seq[ContactData] = Seq.empty, 
                   bdata: Seq[ContactData] = Seq.empty ) = 
    {
      val state = new AggregateContactEditState( Seq(( rawContactA, adata ),
                                                     ( rawContactB, bdata )))
      val aInfo = state.rawContactEditStates(0).accountInfo
      val bInfo = state.rawContactEditStates(1).accountInfo
      (state.aggregatedData, aInfo, bInfo)
    }
      
    def assertPhone( data: Seq[ AggregatedDatum[ Phone ]],
                     number: String, 
                     category: CategoryLabel, 
                     acctInfo: AccountInfo ) =
      assert( 1 == data.count( aDatum =>
        (aDatum.acctInfo eq acctInfo) && 
        aDatum.datum.number == number &&
        aDatum.datum.categoryLabel == category ))

    def assertEmail( data: Seq[ AggregatedDatum[ Email ]],
                     address: String, 
                     category: CategoryLabel, 
                     acctInfo: AccountInfo ) =
      assert( 1 == data.count( aDatum =>
        (aDatum.acctInfo eq acctInfo) && 
        aDatum.datum.address == address &&
        aDatum.datum.categoryLabel == category ))

    def assertNote( data: Seq[ AggregatedDatum[ Note ]],
                    text: String, 
                    acctInfo: AccountInfo ) =
      assert( 1 == data.count( aDatum =>
        (aDatum.acctInfo eq acctInfo) && 
        aDatum.datum.note == text ))

    it ("should aggregate unlike data" ) {

      aggregate( Seq( phone( "617 555 1212", homePhone ),
                      phone( "201 111 1212", workPhone )),
                 Seq( phone( "333 333 3333", customPhone ))) match 
      {
        case (data, aInfo, bInfo) => {
          val aggPhones = data.dataOfType[ Phone ]
          aggPhones.size should be (3)
          assertPhone( aggPhones, "617 555 1212", homePhone,   aInfo )
          assertPhone( aggPhones, "201 111 1212", workPhone,   aInfo )
          assertPhone( aggPhones, "333 333 3333", customPhone, bInfo )
        }
      }

    }
    
    it ("should segregate data by type" ) {

      aggregate( Seq( phone( "617 555 1212", homePhone ),
                      email( "fred@slate.com", homeEmail )),
                 Seq( phone( "333 333 3333", customPhone ))) match
      {
        case (data, aInfo, bInfo) => {

          val aggPhones = data.dataOfType[ Phone ]
          val aggEmails = data.dataOfType[ Email ]

          aggPhones.size should be (2)
          aggEmails.size should be (1)
          assertPhone( aggPhones, "617 555 1212",   homePhone,   aInfo )
          assertPhone( aggPhones, "333 333 3333",   customPhone, bInfo )
          assertEmail( aggEmails, "fred@slate.com", homeEmail,   aInfo )
        }
      }
    }

    it ("should coalesce 'similar' data items") {
      aggregate( Seq( phone( "617 555 1212", homePhone ),
                      phone( "201 111 1212", workPhone )),
                 Seq( phone( "617-555-1212", customPhone ))) match
      {
        case (data, aInfo, bInfo) => {
          val aggPhones = data.dataOfType[ Phone ]

          // Have two "similar" phone numbers.  We're supposed to choose only
          // one, and give the custom label preference, while leaving the
          // "dissimilar" item alone.

          aggPhones.size should be (2)

          assertPhone( aggPhones, "201 111 1212",  workPhone,   aInfo )
          assertPhone( aggPhones, "617-555-1212",  customPhone, bInfo )
        }
      }
    }

    // Separate case in the code, so we need to test it explicitly
    // to assure coverage.

    it ("should aggregate uncategorized data" ) {

      aggregate( Seq( note( "foo" ),
                      email( "fred@slate.com", homeEmail )),
                 Seq( note( "bar" ))) match
      {
        case (data, aInfo, bInfo) => {

          val aggNotes  = data.dataOfType[ Note ]
          val aggEmails = data.dataOfType[ Email ]

          aggNotes.size should be (2)
          aggEmails.size should be (1)
          assertNote( aggNotes, "foo", aInfo )
          assertNote( aggNotes, "bar", bInfo )
          assertEmail( aggEmails, "fred@slate.com", homeEmail,   aInfo )
        }
      }
    }

    it ("should toss empty items") {

      // Standard contacts app has a thing for creating empty notes
      // and nicknames.  We'd rather not show them...

      aggregate ( Seq( note( "foo" )),
                  Seq( note( "" ))) match
      {
        case (data, aInfo, bInfo) => {
          val aggNotes = data.dataOfType[ Note ]
          aggNotes.size should be (1)
          assertNote( aggNotes, "foo", aInfo )
        }
      }      
    }
  }

  // Here, I'm letting the tests know that DISPLAY_NAME is an alias for DATA1,
  // and the other fields are DATAX for X <> 1.  (I'm keeping that knowledge
  // out of the app to try to keep it readable, but it's handy here...)

  describe( "structured name mapper" ) {

    it ("should send display name only if structured fields unset") {

      val name = (new StructuredName)
        .setProperty( "displayName", "Jim Smith" )

      val pairs = ContactData.structuredNames.dataPairs( name )
      val dataKeys = pairs.map{ _._1 }.filter{ _.startsWith("data") }

      dataKeys.toSeq should equal (Seq( "data1" ))

      // Make sure we have other fields.
      pairs.map{ _._1 } should contain ("raw_contact_id")
    }

    it ("should send all structured name fields if any are set") {
      val name = (new StructuredName)
        .setProperty( "displayName", "Jim Smith" )
        .setProperty( "givenName", "Jim" )
        .setProperty( "familyName", "Smith" )

      name.displayName should equal ("Jim Smith")
      name.givenName should equal ("Jim")

      val pairs = ContactData.structuredNames.dataPairs( name )
      val dataKeys = pairs.map{ _._1 }.filter{ _.startsWith("data") }

      dataKeys should contain ("data1")
      dataKeys should contain ("data2") // first name; set
      dataKeys should contain ("data7") // phonetic foo; unset
      pairs.map{ _._1 } should contain ("raw_contact_id") 
    }
  }

  describe( "category label handling" ) {

    val TYPE_HOME   = CDK.Phone.TYPE_HOME
    val TYPE_CUSTOM = CDK.BaseTypes.TYPE_CUSTOM

    val homePhone = (new Phone).setProperty( "categoryTag", TYPE_HOME )
    val customPhone = 
      (new Phone).setProperty( "categoryTag", TYPE_CUSTOM )
                 .setProperty( "label", "FOAF Mobile" )

    def assertCategory( tf: CategoryLabel, tag: Int, label: String ) = {
      tf.tag   should equal (tag)
      tf.label should equal (label)
    }

    it( "should render standard labels correctly" ) {
      assertCategory( homePhone.categoryLabel, TYPE_HOME, null )
    }
    it( "should render custom labels correctly" ) {
      assertCategory( customPhone.categoryLabel, TYPE_CUSTOM, "FOAF Mobile" )
    }
    it ( "should be able to set standard labels" ) {
      val hackedPhone = (new Phone).categoryLabel_:=( 
        CategoryLabel( TYPE_HOME, null ))
      assertCategory( hackedPhone.categoryLabel, TYPE_HOME, null )
    }
    it ( "should be able to set custom labels" ) {
      val hackedPhone = (new Phone).categoryLabel_:=( 
        CategoryLabel( TYPE_CUSTOM, "car" ))
      assertCategory( hackedPhone.categoryLabel, TYPE_CUSTOM, "car" )
    }
  }

  describe( "AccountInfo mapping" ){

    it ("should recognize known account types") {
      val rawc = new RawContact( accountType = "org.positronicnet.test" )
      val acctInfo = AccountInfo.forRawContact( rawc )
      assert( acctInfo.isInstanceOf[ TestAccountInfo ] )
    }

    it ("should give an 'OtherAccountInfo' for unknown accounts") {
      val rawc = new RawContact( accountType = "org.example" )
      val acctInfo = AccountInfo.forRawContact( rawc )
      assert( acctInfo.isInstanceOf[ OtherAccountInfo ] )
    }
  }

  describe( "datakind management in edit states" ){

    // Make a RawContactEditState with the limits on data kinds described
    // above, in text fixture setup...

    def makeRawc = new RawContact( accountType = "org.positronicnet.test" )
    def makeRawContactEditState = {
      val rawc = makeRawc
      val agg = new AggregateContactEditState( Seq( (rawc, Seq.empty) ))
      agg.rawContactEditStates(0)
    }

    it ("should impose no limits unless required") {

      val state = makeRawContactEditState
      import CDK.Email._

      for (i <- Range( 0, 10 )) 
        state.updateItem( email( "fred@fred.com", homeEmail ))

      val availCats = state.availableCategories( email( "", homeEmail ))

      availCats.map{ _.tag } should contain ( TYPE_HOME )
    }

    it ("should correctly count available data") {

      import CDK.Phone._
      import CDK.BaseTypes._

      val state = makeRawContactEditState
      state.updateItem( phone( "617 617 6176", homePhone ))
      state.updateItem( phone( "617 617 6177", homePhone ))
      state.updateItem( phone( "617 617 6178", workPhone ))

      val cats = state.currentCategories( phone("", homePhone ))
      cats( TYPE_HOME )   should be (2)
      cats( TYPE_WORK )   should be (1)
      cats( TYPE_CUSTOM ) should be (0)
    }

    it ("should respect limits on particular categories") {

      import CDK.Phone._

      val state = makeRawContactEditState
      def homePhone( s: String ) = phone( s, CategoryLabel( TYPE_HOME, null ))

      val dummyPhone = new Phone
      def phoneCategories = state.availableCategories( dummyPhone )

      val acctInfo = state.accountInfo

      // limit on home phones is two

      phoneCategories.map{ _.tag } should contain (TYPE_HOME) // have 0

      state.updateItem( homePhone( "617 555 1212" ))
      phoneCategories.map{ _.tag } should contain (TYPE_HOME) // have 1

      state.updateItem( homePhone( "617 555 5555" ))
      phoneCategories.map{ _.tag } should (not contain (TYPE_HOME)) // have 2
    }
    
    it ("should respect limits on DataKind records as a group") {

      import CDK.Phone._
      val state = makeRawContactEditState
      val dummyPhone = new Phone
      def phoneCategories = state.availableCategories( dummyPhone )
      
      state.updateItem( phone( "617 555 5555", workPhone ))
      state.updateItem( phone( "617 555 5555", workPhone ))

      phoneCategories.map{ _.tag } should (not contain (TYPE_WORK)) // have 2
      phoneCategories.map{ _.tag } should contain (TYPE_HOME)

      state.updateItem( phone( "617 555 5555", homePhone ))
      phoneCategories.map{ _.tag } should have size (0) // be empty; now 3 total
    }

    it ("should suggest categories with no exemplars, if any") {
      
      import CDK.Phone._
      val state = makeRawContactEditState
      val dummyPhone = new Phone

      def processedPhone = 
        state.prepareForInsert( dummyPhone ).get.asInstanceOf[Phone]
      def newCategoryTag = processedPhone.categoryLabel.tag
      
      newCategoryTag should be (TYPE_HOME) // with nothing present

      state.updateItem( phone( "555 555 5555", homePhone ))
      newCategoryTag should be (TYPE_WORK) // with nothing present

      state.updateItem( phone( "555 555 5555", workPhone ))
      newCategoryTag should be (TYPE_HOME) // should skip custom; use unfull
    }

  }

}
