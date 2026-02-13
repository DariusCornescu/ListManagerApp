# app/seed.py
from sqlalchemy.orm import Session
from .database import SessionLocal, engine
from . import models
import logging

logging.basicConfig(level=logging.INFO)

def seed_database():
    db = SessionLocal()
    
    try:
        if db.query(models.Distributor).count() > 0:
            logging.info("Database already seeded!")
            return
        
        logging.info("Seeding database...")
        
        # Create distributors
        distributors_data = [
            "METRO CASH&CARRY ROMANIA S.R.L.",
            "ROPLAST IMPEX SRL",
            "NICO ACTIV IMPEX SRL",
            "AGENDA INTERNATIONAL SRL",
            "GLOBELA TRADE SRL",
            "ALEXIM TOP SRL",
            "SERVICE TOP AGRO S.R.L.",
            "REVOLY TRADING S.R.L.",
            "JUGUAR IMP SRL",
            "COCLETCOMPEX SRL",
            "SIMBOL M COM SRL",
            "EVERPRO INTERNATIONAL CONSTRUCTION SRL",
            "ACADEM S.R.L.",
            "FLOWER DELIVERY SRL",
            "ROYAL PROD ACTIV S.R.L.",
            "METAL FREE ONLY SRL",
            "MEFIM AGRO SRL",
            "AUCHAN ROMANIA SA",
            "FAMILGO TRADING SRL",
            "SCULE SI UNELTE SRL",
            "SUPERMARKET LA COCOS SRL",
            "ACORD PLUS SRL",
            "MIRO SRL",
            "WISTEL COM SRL",
            "DECENU NOI RETAIL SRL",
            "ESCAR COM SRL",
            "ELEMENTS EMPIRE SRL",
            "DEDEMAN SRL",
            "CRISCO S.R.L.",
            "RONI TREND COSMETICS SRL",
            "COLEZIONE FASHION SRL",
            "S.C. LUCY STYLE 2000 S.R.L",
            "LOA INTERNATIONAL S.R.L",
            "BIROPAPER EXPERT INTERNATIONAL",
            "ONE GROUP IMPEX SRL",
            "COMANZIORICE SERV S.R.L.",
            "DURIS NEW DIVERTA SRL",
            "SEDONA S.R.L.",
            "MADAGLIR SRL",
            "MERCATO BIKE SRL",
            "LEGEND COM SRL",
            "BIAS SOLID GROUP SRL",
            "FERO-CHIM IMPORT EXPORT S.R.L.",
            "DCN EU RETAIL",
            "VITAVIE S.R.L.",
            "EMER STAR SRL",
            "WEBTRADE MARKETING SRL",
            "DOMAX SYSTEM DISTRIBUTION S.R.L.",
        ]
        
        distributors = [models.Distributor(distributor_name=name) for name in distributors_data]
        db.add_all(distributors)
        db.commit()
        
        # Refresh to get IDs
        for d in distributors:
            db.refresh(d)
        
        logging.info(f"Created {len(distributors)} distributors")
        
        # ===== SEED PRODUCTS =====
        # Sample products for each distributor (using first few distributors)
        products_data = [
            # METRO products
            {"name": "Lapte Zuzu 1L", "distributor_name": "METRO CASH&CARRY ROMANIA S.R.L.", "aliases": "lapte, zuzu"},
            {"name": "Pâine albă 500g", "distributor_name": "METRO CASH&CARRY ROMANIA S.R.L.", "aliases": "paine, franzela"},
            {"name": "Unt President 200g", "distributor_name": "METRO CASH&CARRY ROMANIA S.R.L.", "aliases": "unt"},
            {"name": "Ouă M 10buc", "distributor_name": "METRO CASH&CARRY ROMANIA S.R.L.", "aliases": "oua, ou"},
            {"name": "Cașcaval Hochland 250g", "distributor_name": "METRO CASH&CARRY ROMANIA S.R.L.", "aliases": "cascaval, branza"},
            
            # AUCHAN products
            {"name": "Apă Borsec 2L", "distributor_name": "AUCHAN ROMANIA SA", "aliases": "apa, borsec"},
            {"name": "Coca Cola 2L", "distributor_name": "AUCHAN ROMANIA SA", "aliases": "cola, coca"},
            {"name": "Suc Fanta 1.5L", "distributor_name": "AUCHAN ROMANIA SA", "aliases": "fanta, suc portocale"},
            {"name": "Bere Ursus 0.5L", "distributor_name": "AUCHAN ROMANIA SA", "aliases": "bere, ursus"},
            {"name": "Vin Cotnari 0.75L", "distributor_name": "AUCHAN ROMANIA SA", "aliases": "vin, cotnari"},
            
            # DEDEMAN products
            {"name": "Șuruburi 4x40mm 100buc", "distributor_name": "DEDEMAN SRL", "aliases": "suruburi, surub"},
            {"name": "Cheie reglabilă 200mm", "distributor_name": "DEDEMAN SRL", "aliases": "cheie, scule"},
            {"name": "Bandă izolantă neagră", "distributor_name": "DEDEMAN SRL", "aliases": "banda, izolanta"},
            {"name": "Ciocan 500g", "distributor_name": "DEDEMAN SRL", "aliases": "ciocan"},
            {"name": "Șurubelniță PH2", "distributor_name": "DEDEMAN SRL", "aliases": "surubelnita"},
            
            # ROPLAST products
            {"name": "Pungi plastic 100buc", "distributor_name": "ROPLAST IMPEX SRL", "aliases": "pungi, plastic"},
            {"name": "Folie stretch 500m", "distributor_name": "ROPLAST IMPEX SRL", "aliases": "folie, stretch"},
            {"name": "Cutii carton 40x30x20", "distributor_name": "ROPLAST IMPEX SRL", "aliases": "cutii, carton"},
            
            # FLOWER DELIVERY products
            {"name": "Trandafiri roșii 10buc", "distributor_name": "FLOWER DELIVERY SRL", "aliases": "trandafiri, flori"},
            {"name": "Lalele mix 15buc", "distributor_name": "FLOWER DELIVERY SRL", "aliases": "lalele, flori"},
            {"name": "Buchet mixt mare", "distributor_name": "FLOWER DELIVERY SRL", "aliases": "buchet, flori"},
            
            # SEDONA products
            {"name": "Ceai verde bio 100g", "distributor_name": "SEDONA S.R.L.", "aliases": "ceai, verde"},
            {"name": "Miere bio 500g", "distributor_name": "SEDONA S.R.L.", "aliases": "miere"},
            {"name": "Semințe chia 250g", "distributor_name": "SEDONA S.R.L.", "aliases": "seminte, chia"},
        ]
        
        # Create distributor name to ID mapping
        distributor_map = {d.distributor_name: d.id for d in distributors}
        
        products = []
        for p_data in products_data:
            dist_id = distributor_map.get(p_data["distributor_name"])
            if dist_id:
                products.append(models.Product(
                    name=p_data["name"],
                    distributor_id=dist_id,
                    aliases=p_data.get("aliases")
                ))
        
        db.add_all(products)
        db.commit()
        
        logging.info(f"Created {len(products)} products")
        logging.info("Database seeded successfully!")
        
    finally:
        db.close()

if __name__ == "__main__":
    models.Base.metadata.create_all(bind=engine)
    seed_database()