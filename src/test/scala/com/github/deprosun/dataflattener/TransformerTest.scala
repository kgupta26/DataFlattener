package com.github.deprosun.dataflattener

import com.github.deprosun.dataflattener.model.{MapperContext, PathName, SimpleJsonPathContext, StraightMappingContext}
import com.github.deprosun.dataflattener.transform.{Column, Row, Table, Transformer}
import org.json4s.JsonAST.{JValue, _}
import org.json4s.native.JsonMethods._
import org.slf4j.{Logger, LoggerFactory}

import scala.language.postfixOps
import scala.util.Random

class TransformerTest extends TestStyle {


  def getLogger: Logger = LoggerFactory.getLogger("test")

  describe("A transformer with only string and int as extraction rules in the world") {

    val transformer = new Transformer {

      override val logger: Logger = getLogger

      //define your custom transformation functions
      override val udfMap: Map[String, this.MapFunc] = Map()

    }

    val json: JValue = parse(
      """
        |{
        | "a" : 5,
        | "b" : {
        |   "c" : "foo"
        | }
        |}
      """.stripMargin
    )

    it("should not throw error even when a varchar is requested from an int") {

      val mapping = StraightMappingContext(
        SimpleJsonPathContext(path = List(PathName("a"))),
        desiredColumnName = "newColumnA",
        dataType = "varchar",
        precision = Nil,
        isNull = false,
        attributes = Nil
      )

      val actual = transformer.getColumnStraight(json, mapping)
      val expected = Column("newColumnA", StraightMappingContext(SimpleJsonPathContext(List(PathName("a"))), "newColumnA", "varchar", List(), false, List()), JInt(5))
      assert(expected == actual)

    }

    it("should be able to retrieve int value from a path") {

      val mapping = StraightMappingContext(
        SimpleJsonPathContext(path = List(PathName("a"))),
        desiredColumnName = "newColumnA",
        dataType = "int",
        precision = Nil,
        isNull = false,
        attributes = Nil
      )

      transformer.getColumnStraight(json, mapping) match {
        case Column(colName, _, value) =>
          assert(colName == mapping.desiredColumnName, "Column names don't match.")
          value match {
            case JInt(x) => assert(x == 5, "Column values don't match.")
            case _ => throw new RuntimeException("should not get here")
          }
        case _ => throw new RuntimeException("should not get here")
      }

    }

    it("should be able to retrieve value from nested structure") {

      val mapping = StraightMappingContext(
        SimpleJsonPathContext(path = List(PathName("b"), PathName("c"))),
        desiredColumnName = "newColumnA",
        dataType = "varchar",
        precision = Nil,
        isNull = false,
        attributes = Nil
      )

      transformer.getColumnStraight(json, mapping) match {
        case Column(colName, _, value) =>
          assert(colName == mapping.desiredColumnName, "Column names don't match.")
          value match {
            case JString(x) => assert(x == "foo", "Column values don't match.")
            case _ => throw new RuntimeException("should not get here")
          }
      }

    }
  }

  describe("A transformer that only produces json") {

    val transformer: Transformer = new Transformer {

      override val logger: Logger = getLogger

      override val udfMap: Map[String, this.MapFunc] = Map()

      def extractJson(json: JValue): Option[Any] = Option(json)

    }

    val json: JValue = parse(
      """
        |{
        | "a" : 5,
        | "b" : {
        |   "c" : "foo"
        | }
        |}
      """.stripMargin
    )


    it("should be able to retrieve json struct") {

      val mapping = StraightMappingContext(
        SimpleJsonPathContext(path = List(PathName("b"))),
        desiredColumnName = "newColumnA",
        dataType = "json",
        precision = Nil,
        isNull = false,
        attributes = Nil
      )

      val expectedValue = json \ mapping.path.asInstanceOf[SimpleJsonPathContext].path.head.id

      transformer.getColumnStraight(json, mapping) match {
        case Column(colName, _, value) =>
          assert(colName == mapping.desiredColumnName, "Column names don't match.")
          value match {
            case x => assert(x == expectedValue, "Column values don't match.")
          }
      }

    }

  }

  describe("Sample transformation") {

    def superSafeEncryptionApi(s: String) = {
      s.reverse.foldLeft("") { (acc, x) =>
        acc + Random.nextPrintableChar() + x
      }
    }

    val transformer: Transformer = new Transformer {

      override val logger: Logger = getLogger

      def encrypt(json: List[JValue]): JValue = json match {
        case JString(s) :: Nil => JString(superSafeEncryptionApi(s))
        case x =>
          throw new UnknownError(s"Should not get here $x")
      }

      def combineParties(json: List[JValue]): JValue = {
        val insured: JValue = json.head \ "dob"
        val owners: JArray = json.last.extract[JArray]

        //        val ownersDob: List[(String, JValue)] = owners filterField { case (x, _) => x == "dob" } map { case (_, v) => "dateOfBirth" -> v }

        val ownersDob = owners filterField {
          case (x, _) => x == "dob" || x == "govId" || x == "govIdType"
        } map {
          case ("dob", v) =>
            "dateOfBirth" -> v
          case ("govId", v) =>
            "identification" -> JObject("govId" -> v)
          case ("govIdType", v) =>
            "identification" -> JObject("govIdType" -> v)
          case _ => throw new RuntimeException("should not get here")
        }

        val allDobs: List[JField] = ("dateOfBirth" -> insured) :: ownersDob

        val array: JValue = JArray(allDobs map { x =>
          JObject(x)
        })


        array
      }

      def onlyAdvisorId(advisors: List[JValue]): JValue = {
        advisors match {
          case Nil => JNothing
          case (arr: JArray) :: Nil =>
            val allDobs: List[JField] = arr filterField { case (x, _) => x == "advisorId" } map { case (_, v) => "advisorId" -> v }

            val array: JValue = JArray(allDobs map { x =>
              JObject(x)
            })

            array
          case _ => throw new RuntimeException("should not get here")
        }
      }

      def extractJson(json: JValue): Option[Any] = Option(json)

      def addField(json: List[JValue]): JValue = JString("7")

      override val udfMap: Map[String, List[JValue] => JValue] = Map(
        "encrypt" -> encrypt,
        "combineParties" -> combineParties,
        "onlyAdvisorId" -> onlyAdvisorId,
        "addField" -> addField
      )
    }

    it("CoverPath to A360") {

      def runTest(sourceJSON: String, mapper: String): String = {
        val json = parse(sourceJSON)
        MapperContext.getMappers(mapper) flatMap { x =>
          transformer.transform(json, x) map { y => //testing transformer.transform
            y.toJsonString
          }
        } head
      }

      val testJSON =
        """
          |{
          |    "eventHeader":{
          |        "eventType": "applicationSubmitted",
          |        "eventGroup": "Policy",
          |        "eventDateTime": "2020-07-21T04:10:18.861Z",
          |        "eventOccuredDateTime": "2020-07-21T04:10:18.861Z",
          |        "eventId": "efa2d474-02f4-4d0e-893b-5bda0eed016d",
          |        "eventRequester": "coverpath"
          |    },
          |    "eventBody": {
          |        "policy": {
          |            "policyNumber": "402000000",
          |            "productType": "TermLife",
          |            "productCode": "CPL100",
          |            "companyCode": "CP45",
          |            "faceAmount": 500000,
          |            "status": "submitted",
          |            "externalKey": "HAP"
          |        },
          |        "insured": {
          |            "nonNatural": false,
          |            "entityName": null,
          |            "firstName": "Donald",
          |            "lastName": "Duck",
          |            "middleName": "Duey",
          |            "dob": "2000-06-23T00:00:00Z",
          |            "govId": "109984944",
          |            "govIdType": "SSN",
          |            "address": {
          |                "street": "38 Haggerty Hill Rd",
          |                "city": "Juneau",
          |                "state": "AK",
          |                "zip": "99801",
          |                "country": "US"
          |            }
          |        },
          |        "owners": [
          |            {
          |                "nonNatural": false,
          |                "entityName": null,
          |                "firstName": "John",
          |                "lastName": "Smith",
          |                "middleName": "Will",
          |                "dob": "1987-07-02T00:00:00Z",
          |                "govId": "341127689",
          |                "govIdType": "SSN",
          |                "address": {
          |                    "street": "38 Haggerty Hill Rd",
          |                    "city": "Juneau",
          |                    "state": "AK",
          |                    "zip": "99801",
          |                    "country": "US"
          |                }
          |            },
          |            {
          |                "nonNatural": false,
          |                "entityName": null,
          |                "firstName": "Alice",
          |                "lastName": "Smith",
          |                "middleName": "Mary",
          |                "dob": "1986-03-21T00:00:00Z",
          |                "govId": "125677689",
          |                "govIdType": "SSN",
          |                "address": {
          |                    "street": "38 Haggerty Hill Rd",
          |                    "city": "Juneau",
          |                    "state": "AK",
          |                    "zip": "99801",
          |                    "country": "US"
          |                }
          |            }
          |        ],
          |        "advisors": [
          |            {
          |                "advisorId": "AA438607",
          |                "role": "primary"
          |            },
          |            {
          |                "advisorId": "AA424123",
          |                "role": "secondary"
          |            }
          |        ]
          |    }
          |}
        """.stripMargin

      val mapper =
        """
          |TOPIC Donut (
          |
          |	MAPPING (
          |		eventBody.policy.policyNumber                               =   policyNumber          VARCHAR       NOT NULL
          |   eventBody.policy.companyCode                                =   companyCode           VARCHAR       NOT NULL
          |   eventBody.policy.status                                     =   status                VARCHAR       NOT NULL
          |   eventBody.policy.faceAmount                                 =   faceAmount            INT           NOT NULL
          |   addField()                                                  =   addedColumn           VARCHAR       NOT NULL
          |   LIST parties FROM appendToList(eventBody.owners, eventBody.insured) (
          |     dob                   = dateOfBirth  VARCHAR NOT NULL
          |     OBJECT identification FROM (govId, govIdType) (
          |       govId               = govId      VARCHAR NOT NULL
          |       govIdType           = govIdType  VARCHAR NOT NULL
          |     )
          |   )
          |	)
          |)
        """.stripMargin

      val result = runTest(testJSON, mapper)

      val expected =
        """
          |{
          |    "Donut": [
          |        {
          |            "policyNumber": "402000000",
          |            "companyCode": "CP45",
          |            "status": "submitted",
          |            "faceAmount": 500000,
          |            "addedColumn": "7",
          |            "parties": [
          |                {
          |                    "dateOfBirth": "1987-07-02T00:00:00Z",
          |                    "identification": {
          |                        "govId": "341127689",
          |                        "govIdType": "SSN"
          |                    }
          |                },
          |                {
          |                    "dateOfBirth": "1986-03-21T00:00:00Z",
          |                    "identification": {
          |                        "govId": "125677689",
          |                        "govIdType": "SSN"
          |                    }
          |                },
          |                {
          |                    "dateOfBirth": "2000-06-23T00:00:00Z",
          |                    "identification": {
          |                        "govId": "109984944",
          |                        "govIdType": "SSN"
          |                    }
          |                }
          |            ]
          |        }
          |    ]
          |}
        """.stripMargin

      assert(parse(result) == parse(expected))
    }

  }

  describe("Sample transformation with explode") {


    val transformer: Transformer = new Transformer {
      def superSafeEncryptionApi(s: String): String = {
        s.reverse.foldLeft("") { (acc, x) =>
          acc + Random.nextPrintableChar() + x
        }
      }

      override val logger: Logger = getLogger

      def encrypt(json: List[JValue]): JValue = json match {
        case JString(s) :: Nil => JString(superSafeEncryptionApi(s))
        case x =>
          throw new UnknownError(s"Should not get here $x")
      }

      def combineParties(json: List[JValue]): JValue = {
        val insured: JValue = json.head \ "dob"
        val owners: JArray = json.last.extract[JArray]

        //        val ownersDob: List[(String, JValue)] = owners filterField { case (x, _) => x == "dob" } map { case (_, v) => "dateOfBirth" -> v }

        val ownersDob = owners filterField {
          case (x, _) => x == "dob" || x == "govId" || x == "govIdType"
        } map {
          case ("dob", v) =>
            "dateOfBirth" -> v
          case ("govId", v) =>
            "identification" -> JObject("govId" -> v)
          case ("govIdType", v) =>
            "identification" -> JObject("govIdType" -> v)
          case _ => throw new RuntimeException("should not get here")
        }

        val allDobs: List[JField] = ("dateOfBirth" -> insured) :: ownersDob

        val array: JValue = JArray(allDobs map { x =>
          JObject(x)
        })


        array
      }

      def onlyAdvisorId(advisors: List[JValue]): JValue = {
        advisors match {
          case Nil => JNothing
          case (arr: JArray) :: Nil =>
            val allDobs: List[JField] = arr filterField { case (x, _) => x == "advisorId" } map { case (_, v) => "advisorId" -> v }

            val array: JValue = JArray(allDobs map { x =>
              JObject(x)
            })

            array
          case _ => throw new RuntimeException("should not get here")
        }
      }

      def extractJson(json: JValue): Option[Any] = Option(json)

      def addField(json: List[JValue]): JValue = JString("7")

      override val udfMap: Map[String, List[JValue] => JValue] = Map(
        "encrypt" -> encrypt,
        "combineParties" -> combineParties,
        "onlyAdvisorId" -> onlyAdvisorId,
        "addField" -> addField
      )
    }

    it("CoverPath to A360") {

      val json = parse(
        """
          |{
          |    "eventHeader":{
          |        "eventType": "applicationSubmitted",
          |        "eventGroup": "Policy",
          |        "eventDateTime": "2020-07-21T04:10:18.861Z",
          |        "eventOccuredDateTime": "2020-07-21T04:10:18.861Z",
          |        "eventId": "efa2d474-02f4-4d0e-893b-5bda0eed016d",
          |        "eventRequester": "coverpath"
          |    },
          |    "eventBody": {
          |        "policy": {
          |            "policyNumber": "402000000",
          |            "productType": "TermLife",
          |            "productCode": "CPL100",
          |            "companyCode": "CP45",
          |            "faceAmount": 500000,
          |            "status": "submitted",
          |            "externalKey": "HAP"
          |        },
          |        "insured": {
          |            "nonNatural": false,
          |            "entityName": null,
          |            "firstName": "Donald",
          |            "lastName": "Duck",
          |            "middleName": "Duey",
          |            "dob": "2000-06-23T00:00:00Z",
          |            "govId": "109984944",
          |            "govIdType": "SSN",
          |            "address": {
          |                "street": "38 Haggerty Hill Rd",
          |                "city": "Juneau",
          |                "state": "AK",
          |                "zip": "99801",
          |                "country": "US"
          |            }
          |        },
          |        "owners": [
          |            {
          |                "nonNatural": false,
          |                "entityName": null,
          |                "firstName": "John",
          |                "lastName": "Smith",
          |                "middleName": "Will",
          |                "dob": "1987-07-02T00:00:00Z",
          |                "govId": "341127689",
          |                "govIdType": "SSN",
          |                "address": {
          |                    "street": "38 Haggerty Hill Rd",
          |                    "city": "Juneau",
          |                    "state": "AK",
          |                    "zip": "99801",
          |                    "country": "US"
          |                }
          |            },
          |            {
          |                "nonNatural": false,
          |                "entityName": null,
          |                "firstName": "Alice",
          |                "lastName": "Smith",
          |                "middleName": "Mary",
          |                "dob": "1986-03-21T00:00:00Z",
          |                "govId": "125677689",
          |                "govIdType": "SSN",
          |                "address": {
          |                    "street": "38 Haggerty Hill Rd",
          |                    "city": "Juneau",
          |                    "state": "AK",
          |                    "zip": "99801",
          |                    "country": "US"
          |                }
          |            }
          |        ],
          |        "advisors": [
          |            {
          |                "advisorId": "AA438607",
          |                "role": "primary"
          |            },
          |            {
          |                "advisorId": "AA424123",
          |                "role": "secondary"
          |            }
          |        ]
          |    }
          |}
        """.stripMargin
      )

      val mapper =
        """
          |TOPIC Donut (
          |
          |	MAPPING (
          |		eventBody.policy.policyNumber                               =   policyNumber          VARCHAR       NOT NULL
          |   eventBody.policy.companyCode                                =   companyCode           VARCHAR       NOT NULL
          |   eventBody.policy.status                                     =   status                VARCHAR       NOT NULL
          |   eventBody.policy.faceAmount                                 =   faceAmount            INT           NOT NULL
          |   addField()                                                  =   addedColumn           VARCHAR       NOT NULL
          |   EXPLODE eventBody.advisors BROADCAST (eventBody.policy.policyNumber AS pNumber)(
          |        advisorId = aId VARCHAR NOT NULL
          |        role = rId VARCHAR NOT NULL
          |        pNumber = pNumber VARCHAR NOT NULL
          |   )
          |   LIST parties FROM eventBody.owners (
          |     dob                   = dateOfBirth  VARCHAR NOT NULL
          |     OBJECT identification FROM (govId, govIdType) (
          |       govId               = govId      VARCHAR NOT NULL
          |       govIdType           = govIdType  VARCHAR NOT NULL
          |     )
          |   )
          |	)
          |)
        """.stripMargin

      MapperContext.getMappers(mapper) foreach { x =>
        transformer.transform(json, x) foreach { y =>
          println(y.toJsonString)
        }
      }
    }

  }

  describe("A full transformation with Parent and Child tables") {
    val transformer: Transformer = new Transformer {

      override val logger: Logger = LoggerFactory.getLogger("demo")

      def extractJson(json: JValue): Option[Any] = Option(json)

      override val udfMap: Map[String, this.MapFunc] = Map()
    }

    it("should work") {

      val json = parse(
        """
          |{
          |    	"donutUniqueId": "0001",
          |    	"type": "donut",
          |    	"name": "Cake",
          |    	"ppu": 0.55,
          |    	"batters": {
          |    			"batter": [
          |    					{ "id": "1001", "type": "Regular" },
          |    					{ "id": "1002", "type": "Chocolate" },
          |    					{ "id": "1003", "type": "Blueberry" },
          |    					{ "id": "1004", "type": "Devil's Food" }
          |    				]
          |    		},
          |    	"topping":
          |    		[
          |    			{ "id": "5001", "type": "None" },
          |    			{ "id": "5002", "type": "Glazed" },
          |    			{ "id": "5005", "type": "Sugar" },
          |    			{ "id": "5007", "type": "Powdered Sugar" },
          |    			{ "id": "5006", "type": "Chocolate with Sprinkles" },
          |    			{ "id": "5003", "type": "Chocolate" },
          |    			{ "id": "5004", "type": "Maple" }
          |    		]
          |}
        """.stripMargin
      )

      val mapperConfig =
        """
          |TOPIC Donut (
          |
          |	MAPPING (
          |		donutUniqueId      = donutUID   VARCHAR    NOT NULL  PK
          |		name               = donutName  VARCHAR    NOT NULL
          |	)
          |
          |	TOPIC Batter FROM batters.batter BROADCAST (donutUniqueId AS donutId) (
          |		MAPPING (
          |					id        = batterUID    		    VARCHAR    NOT NULL  PK
          |					donutId   = donutParentId       VARCHAR    NOT NULL  FK
          |					type      = batterType   		    VARCHAR    NOT NULL
          |		)
          |	)
          |
          |)
        """.stripMargin

      val mappers = MapperContext.getMappers(mapperConfig)


      mappers foreach { x =>

        val transformed: List[Table] = transformer.transform(json, x)

        //lets print
        transformed foreach { y =>
          println(y.toJsonString)
        }

        //lets also assert few things
        assert(transformed.length == 2) //one for donut and batter

        val List(donut, batter) = transformed

        val donutTable = Table("Donut", List(
          Row(
            List(
              Column(
                mappers.head.mappings.head.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.head.mappings.head.asInstanceOf[StraightMappingContext],
                JString("0001")
              ),
              Column(
                mappers.head.mappings.last.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.head.mappings.last.asInstanceOf[StraightMappingContext],
                JString("Cake")
              )
            )
          )
        ))

        assert(donut == donutTable)

        val batterTable = Table("Batter", List(
          Row(
            List(
              Column(
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext],
                JString("1001")
              ),
              Column(
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext],
                JString("0001")
              ),
              Column(
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext],
                JString("Regular")
              )
            )
          ),
          Row(
            List(
              Column(
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext],
                JString("1002")
              ),
              Column(
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext],
                JString("0001")
              ),
              Column(
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext],
                JString("Chocolate")
              )
            )
          ),
          Row(
            List(
              Column(
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext],
                JString("1003")
              ),
              Column(
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext],
                JString("0001")),
              Column(
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext],
                JString("Blueberry")
              )
            )
          ),
          Row(
            List(
              Column(
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext],
                JString("1004")
              ),
              Column(
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext],
                JString("0001")
              ),
              Column(
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext].desiredColumnName,
                mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext],
                JString("Devil's Food")
              )
            )
          )
        ))

        assert(batter == batterTable)

      }


    }
  }

  describe("A full transformation with Parent and Child tables " +
    "and where an internal array field is mapped") {
    val transformer: Transformer = new Transformer {

      override val logger: Logger = LoggerFactory.getLogger("demo")

      def extractJson(json: JValue): Option[Any] = Option(json)

      override val udfMap: Map[String, this.MapFunc] = Map()
    }

    it("should work") {

      val json = parse(
        """
          |{
          |    	"donutUniqueId": "0001",
          |    	"type": "donut",
          |    	"name": "Cake",
          |    	"ppu": 0.55,
          |     "gateType" : "someType",
          |    	"batters": {
          |    			"batter": [
          |    					{ "id": "1001", "type": "Regular" },
          |    					{ "id": "1002", "type": "Chocolate" },
          |    					{ "id": "1003", "type": "Blueberry" },
          |    					{ "id": "1004", "type": "Devil's Food" }
          |    				]
          |    		},
          |    	"topping":
          |    		[
          |    			{ "id": "5001", "type": "None" },
          |    			{ "id": "5002", "type": "Glazed" },
          |    			{ "id": "5005", "type": "Sugar" },
          |    			{ "id": "5007", "type": "Powdered Sugar" },
          |    			{ "id": "5006", "type": "Chocolate with Sprinkles" },
          |    			{ "id": "5003", "type": "Chocolate" },
          |    			{ "id": "5004", "type": "Maple" }
          |    		]
          |}
        """.stripMargin
      )

      val mapperConfig =
        """
          |TOPIC Donut (
          |
          |	MAPPING (
          |		donutUniqueId             = donutUID   VARCHAR    NOT NULL  PK
          |		name                      = donutName  VARCHAR    NOT NULL
          |   OBJECT identification FROM (gateType AS gType) (
          |     gType         = gateTypeId  VARCHAR NOT NULL
          |   )
          |	)
          |)
        """.stripMargin

      val mappers = MapperContext.getMappers(mapperConfig)


      mappers foreach { x =>

        val transformed = transformer.transform(json, x)

        //lets print
        transformed foreach { y =>
          println(y.toJsonString)
        }

        //lets also assert few things
        //        assert(transformed.length == 2) //one for donut and batter
        //
        //        val List(donut, batter) = transformed
        //
        //        val donutTable = Table("Donut", List(
        //          Row(
        //            List(
        //              Column(mappers.head.mappings.head.asInstanceOf[StraightMappingContext], JString("0001")),
        //              Column(mappers.head.mappings.last.asInstanceOf[StraightMappingContext], JString("Cake"))
        //            )
        //          )
        //        ))
        //
        //        assert(donut == donutTable)
        //
        //        val batterTable = Table("Batter", List(
        //          Row(
        //            List(
        //              Column(mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext], JString("1001")),
        //              Column(mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext], JString("0001")),
        //              Column(mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext], JString("Regular"))
        //            )
        //          ),
        //          Row(
        //            List(
        //              Column(mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext], JString("1002")),
        //              Column(mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext], JString("0001")),
        //              Column(mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext], JString("Chocolate"))
        //            )
        //          ),
        //          Row(
        //            List(
        //              Column(mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext], JString("1003")),
        //              Column(mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext], JString("0001")),
        //              Column(mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext], JString("Blueberry"))
        //            )
        //          ),
        //          Row(
        //            List(
        //              Column(mappers.last.children.head.mappings.head.asInstanceOf[StraightMappingContext], JString("1004")),
        //              Column(mappers.last.children.head.mappings(1).asInstanceOf[StraightMappingContext], JString("0001")),
        //              Column(mappers.last.children.head.mappings.last.asInstanceOf[StraightMappingContext], JString("Devil's Food"))
        //            )
        //          )
        //        ))
        //
        //        assert(batter == batterTable)

      }


    }
  }
}
